package com.syntaric.hapi;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Interceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.syntaric.PixManager;
import com.syntaric.ips.IpsAuditRecorder;
import com.syntaric.openehr.OpenEhrAqlUtil;
import com.syntaric.openehr.OpenEhrCdrClient;
import com.syntaric.openehr.OpenEhrCdrException;
import com.syntaric.openehr.OpenEhrCdrRegistry;
import com.syntaric.openfhir.OpenFhirClient;
import com.syntaric.openfhir.OpenFhirException;
import com.syntaric.openfhir.aql.ToAqlRequest;
import com.syntaric.openfhir.aql.ToAqlResponse;
import org.hl7.fhir.r4.model.DeviceRequest;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Intercepts FHIR search GET requests, translates them to openEHR AQLs via OpenFHIR,
 * executes the AQLs against the CDR, converts results back to FHIR, and returns a
 * search Bundle containing only resources matching the requested resource type.
 *
 * <p>Activated when the request path ends with a known FHIR resource type (e.g. {@code /fhir/AllergyIntolerance})
 * and a {@code patient} query parameter is present to resolve the EHR ID.
 */
@Configuration
@Slf4j
@Interceptor
public class QedmSearchInterceptor implements Filter {

    private static final String TEMPLATE_ID = "International Patient Summary";
    private static final Set<String> SUPPORTED_RESOURCE_TYPES = Set.of(
            "AllergyIntolerance", "Condition", "MedicationStatement"
    );

    private static final String X_REQ_ID_HEADER = "x-req-id";

    private final PixManager pixManager;
    private final OpenEhrCdrRegistry openEhrCdrRegistry;
    private final OpenFhirClient openFhirClient;
    private final OpenEhrAqlUtil openEhrAqlUtil;
    private final IpsAuditRecorder auditRecorder;

    public QedmSearchInterceptor(final PixManager pixManager,
                                 final OpenEhrCdrRegistry openEhrCdrRegistry,
                                 final OpenFhirClient openFhirClient,
                                 final OpenEhrAqlUtil openEhrAqlUtil,
                                 final IpsAuditRecorder auditRecorder) {
        this.pixManager = pixManager;
        this.openEhrCdrRegistry = openEhrCdrRegistry;
        this.openFhirClient = openFhirClient;
        this.openEhrAqlUtil = openEhrAqlUtil;
        this.auditRecorder = auditRecorder;
    }

    @Bean
    public FilterRegistrationBean<QedmSearchInterceptor> qedmFilterRegistration() {
        final FilterRegistrationBean<QedmSearchInterceptor> registration = new FilterRegistrationBean<>(this);
        registration.addUrlPatterns("/*");
        registration.setOrder(2);
        return registration;
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response,
                         final FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!"GET".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        final String resourceType = resolveResourceType(httpRequest.getRequestURI());
        if (resourceType == null) {
            chain.doFilter(request, response);
            return;
        }

        final String patientParam = httpRequest.getParameter("patient");
        if (patientParam == null || patientParam.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        // patient param may be "Patient/123" or just "123"
        final String patientId = patientParam.contains("/")
                ? patientParam.substring(patientParam.lastIndexOf('/') + 1)
                : patientParam;

        log.info("QEDM search intercepted: resourceType={}, patient={}", resourceType, patientId);

        try {
            final Bundle bundle = executeSearch(httpRequest, resourceType, patientId);
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            httpResponse.setContentType("application/fhir+json;charset=UTF-8");
            httpResponse.getWriter().write(
                    openFhirClient.getFhirContext().newJsonParser().encodeResourceToString(bundle));
            httpResponse.flushBuffer();
        } catch (final Exception e) {
            log.error("QEDM search failed for resourceType={}, patient={}", resourceType, patientId, e);
            httpResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write("{***REMOVED***"error***REMOVED***":***REMOVED***"" + jsonEscape(e.getMessage()) + "***REMOVED***"}");
            httpResponse.flushBuffer();
        }
    }

    private Bundle executeSearch(final HttpServletRequest httpRequest,
                                 final String resourceType,
                                 final String patientId) {
        final String incomingReqId = httpRequest.getHeader(X_REQ_ID_HEADER);
        final String reqId = (incomingReqId != null && !incomingReqId.isBlank()) ? incomingReqId : UUID.randomUUID().toString();

        final String cdrName = httpRequest.getHeader(OpenEhrCdrRegistry.TARGET_CDR_HEADER);
        final String resolvedCdrName = openEhrCdrRegistry.resolveName(cdrName);

        final String ehrId = pixManager.resolveById(patientId, IpsBundleInterceptor.EHRID_SYSTEM, resolvedCdrName)
                .orElseThrow(() -> new IllegalStateException(
                        "No EHR ID found for patient " + patientId + " on CDR '" + resolvedCdrName + "'"));

        // build fhirPath: /ResourceType + any query params except "patient"
        final String fhirPath = buildFhirPath(httpRequest, resourceType);
        log.debug("Resolved fhirPath={} for ehrId={}", fhirPath, ehrId);

        // step 1 — record the incoming search request
        auditRecorder.record(reqId, 1, "step-1-search",
                "GET " + httpRequest.getRequestURI() + (httpRequest.getQueryString() != null ? "?" + httpRequest.getQueryString() : ""),
                "resourceType=" + resourceType + ", patientId=" + patientId + ", ehrId=" + ehrId,
                null,
                DeviceRequest.RequestIntent.PLAN);

        // step 2 — /openfhir/toaql
        final String toAqlReqText = "templateId=" + TEMPLATE_ID + ", ehrId=" + ehrId + ", fhirPath=" + fhirPath;
        final ToAqlResponse toAqlResponse;
        try {
            toAqlResponse = openFhirClient.getAql(new ToAqlRequest(TEMPLATE_ID, ehrId, fhirPath), reqId);
        } catch (final OpenFhirException e) {
            auditRecorder.recordError(reqId, 2, "step-2-toaql",
                    toAqlReqText,
                    e.toDetailString(),
                    reqId,
                    DeviceRequest.RequestIntent.PLAN);
            throw e;
        }
        if (toAqlResponse.getAqls() == null || toAqlResponse.getAqls().isEmpty()) {
            log.debug("No AQLs returned for fhirPath={}", fhirPath);
            auditRecorder.record(reqId, 2, "step-2-toaql", toAqlReqText, "no AQLs returned", reqId, DeviceRequest.RequestIntent.PLAN);
            return emptySearchBundle();
        }
        final StringBuilder toAqlRespText = new StringBuilder();
        toAqlResponse.getAqls().forEach(a -> toAqlRespText.append("type=").append(a.getType()).append(", aql=").append(a.getAql()).append("***REMOVED***n"));
        auditRecorder.record(reqId, 2, "step-2-toaql", toAqlReqText, toAqlRespText.toString().trim(), reqId, DeviceRequest.RequestIntent.PLAN);

        // step 3 — trigger AQLs against CDR
        final OpenEhrCdrClient cdrClient = openEhrCdrRegistry.resolve(cdrName);
        final List<JsonNode> allRows = new ArrayList<>();
        final StringBuilder aqlReqText = new StringBuilder();
        final StringBuilder aqlRespText = new StringBuilder();
        for (final ToAqlResponse.AqlResponse aqlResponse : toAqlResponse.getAqls()) {
            if (aqlResponse.getType() == ToAqlResponse.AqlType.COMPOSITION) {
                continue;
            }
            aqlReqText.append(aqlResponse.getAql()).append("***REMOVED***n");
            final String openEhrResult;
            try {
                openEhrResult = cdrClient.queryAql(aqlResponse.getAql());
            } catch (final OpenEhrCdrException e) {
                auditRecorder.recordError(reqId, 3, "step-3-trigger-aql",
                        aqlReqText.toString().trim(),
                        e.toDetailString(),
                        null,
                        DeviceRequest.RequestIntent.PLAN);
                throw e;
            }
            aqlRespText.append(openEhrResult).append("***REMOVED***n");
            allRows.addAll(openEhrAqlUtil.extractArchetypeRows(openEhrResult));
        }
        if (!aqlReqText.isEmpty()) {
            auditRecorder.record(reqId, 3, "step-3-trigger-aql",
                    aqlReqText.toString().trim(),
                    aqlRespText.toString().trim(),
                    null,
                    DeviceRequest.RequestIntent.PLAN);
        }

        if (allRows.isEmpty()) {
            return emptySearchBundle();
        }

        // step 4 — /openfhir/tofhir
        final String toFhirReqText = allRows.size() + " archetype rows";
        final String fhirJson;
        try {
            fhirJson = openFhirClient.toFhir(allRows, reqId);
        } catch (final OpenFhirException e) {
            auditRecorder.recordError(reqId, 4, "step-4-tofhir",
                    toFhirReqText,
                    e.toDetailString(),
                    reqId,
                    DeviceRequest.RequestIntent.PLAN);
            throw e;
        }
        auditRecorder.record(reqId, 4, "step-4-tofhir", toFhirReqText, fhirJson, reqId, DeviceRequest.RequestIntent.PLAN);

        final FhirContext fhirContext = openFhirClient.getFhirContext();
        final Bundle resultBundle = fhirContext.newJsonParser().parseResource(Bundle.class, fhirJson);

        // filter to only resources matching the requested type, assign IDs where missing
        final Bundle searchBundle = emptySearchBundle();
        resultBundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(r -> r != null && resourceType.equals(r.getResourceType().name()))
                .forEach(r -> {
                    if (r.getIdElement().isEmpty()) {
                        r.setId(UUID.randomUUID().toString());
                    }
                    searchBundle.addEntry().setResource((Resource) r);
                });

        searchBundle.setTotal(searchBundle.getEntry().size());
        return searchBundle;
    }

    /**
     * Builds the fhirPath as {@code /ResourceType[?params]} excluding the {@code patient} parameter
     * since that is resolved separately via the EHR ID.
     */
    private String buildFhirPath(final HttpServletRequest httpRequest, final String resourceType) {
        final StringBuilder path = new StringBuilder("/").append(resourceType);
        final String queryString = httpRequest.getQueryString();
        if (queryString != null && !queryString.isBlank()) {
            final String filtered = java.util.Arrays.stream(queryString.split("&"))
                    .filter(p -> !p.startsWith("patient="))
                    .reduce((a, b) -> a + "&" + b)
                    .orElse(null);
            if (filtered != null && !filtered.isBlank()) {
                path.append("?").append(filtered);
            }
        }
        return path.toString();
    }

    /**
     * Returns the FHIR resource type from the request URI if it is a supported type,
     * or {@code null} if this request should not be intercepted.
     */
    private String resolveResourceType(final String uri) {
        for (final String type : SUPPORTED_RESOURCE_TYPES) {
            if (uri.endsWith("/" + type) || uri.contains("/" + type + "?")) {
                return type;
            }
        }
        return null;
    }

    private Bundle emptySearchBundle() {
        final Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(0);
        return bundle;
    }

    private static String jsonEscape(final String s) {
        if (s == null) return "";
        return s.replace("***REMOVED******REMOVED***", "***REMOVED******REMOVED******REMOVED******REMOVED***").replace("***REMOVED***"", "***REMOVED******REMOVED******REMOVED***"").replace("***REMOVED***n", "***REMOVED******REMOVED***n").replace("***REMOVED***r", "***REMOVED******REMOVED***r");
    }
}
