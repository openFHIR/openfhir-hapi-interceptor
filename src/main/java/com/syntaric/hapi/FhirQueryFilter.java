package com.syntaric.hapi;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Interceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.syntaric.PixManager;
import com.syntaric.openehr.Constants;
import com.syntaric.openehr.OpenEhrAqlUtil;
import com.syntaric.openehr.OpenEhrCdrClient;
import com.syntaric.openehr.OpenEhrCdrRegistry;
import com.syntaric.openfhir.OpenFhirClient;
import com.syntaric.openfhir.aql.ToAqlRequest;
import com.syntaric.openfhir.aql.ToAqlResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Intercepts FHIR search GET requests, translates them to openEHR AQLs via OpenFHIR,
 * executes the AQLs against the CDR, converts results back to FHIR, and returns a
 * search Bundle containing only resources matching the requested resource type.
 *
 * <p>Which requests are intercepted is fully driven by {@link com.syntaric.InterceptorProperties.FhirQueryFilter}.
 * Each configured rule defines a set of FHIR query parameters (and optionally a resource type
 * via the special {@code _resourceType} key) that must all match the incoming request.
 * The first matching rule's {@code templateId} is used for the OpenFHIR call.
 * If no rule matches the request passes through to HAPI.
 */
@Component
@Slf4j
@Interceptor
public class FhirQueryFilter implements Filter {

    private static final String RESOURCE_TYPE_KEY = "_resourceType";
    private static final String WILDCARD = "*";
    private static final String X_REQ_ID_HEADER = "x-req-id";

    private final PixManager pixManager;
    private final OpenEhrCdrRegistry openEhrCdrRegistry;
    private final OpenFhirClient openFhirClient;
    private final OpenEhrAqlUtil openEhrAqlUtil;
    private final FhirContext fhirContext;
    private final com.syntaric.InterceptorProperties properties;

    @Autowired
    public FhirQueryFilter(final PixManager pixManager,
                           final OpenEhrCdrRegistry openEhrCdrRegistry,
                           final OpenFhirClient openFhirClient,
                           final OpenEhrAqlUtil openEhrAqlUtil,
                           final FhirContext fhirContext,
                           final com.syntaric.InterceptorProperties properties) {
        this.pixManager = pixManager;
        this.openEhrCdrRegistry = openEhrCdrRegistry;
        this.openFhirClient = openFhirClient;
        this.openEhrAqlUtil = openEhrAqlUtil;
        this.fhirContext = fhirContext;
        this.properties = properties;
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response,
                         final FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!HttpMethod.GET.name().equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        final String patientParam = httpRequest.getParameter("patient");
        if (patientParam == null || patientParam.isBlank()) {
            log.info("GET {} — no patient query param, passing through to HAPI", httpRequest.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        final String resourceType = resourceTypeFromUri(httpRequest.getRequestURI());
        final com.syntaric.InterceptorProperties.Rule matchedRule = resolveRule(httpRequest, resourceType);
        if (matchedRule == null) {
            log.info("GET {} — no rule matched, passing through to HAPI", httpRequest.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        // patient param may be "Patient/123" or just "123"
        final String patientId = patientParam.contains("/")
                ? patientParam.substring(patientParam.lastIndexOf('/') + 1)
                : patientParam;

        log.info("GET {} — rule matched, intercepting for patient={}",
                 httpRequest.getRequestURI(), patientId);

        try {
            final Bundle bundle = executeSearch(httpRequest, resourceType, patientId, matchedRule);
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            httpResponse.setContentType("application/fhir+json;charset=UTF-8");
            httpResponse.getWriter().write(fhirContext.newJsonParser().encodeResourceToString(bundle));
            httpResponse.flushBuffer();
        } catch (final Exception e) {
            log.error("Query interception failed for {} patient={}", httpRequest.getRequestURI(), patientId, e);
            httpResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write("{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
            httpResponse.flushBuffer();
        }
    }

    private Bundle executeSearch(final HttpServletRequest httpRequest,
                                 final String resourceType,
                                 final String patientId,
                                 final com.syntaric.InterceptorProperties.Rule rule) {
        final String incomingReqId = httpRequest.getHeader(X_REQ_ID_HEADER);
        final String reqId =
                (incomingReqId != null && !incomingReqId.isBlank()) ? incomingReqId : UUID.randomUUID().toString();

        final String cdrName = httpRequest.getHeader(OpenEhrCdrRegistry.TARGET_CDR_HEADER);
        final String resolvedCdrName = openEhrCdrRegistry.resolveName(cdrName);

        final String ehrId = pixManager.resolveById(patientId, Constants.EHRID_SYSTEM, resolvedCdrName)
                .orElseThrow(() -> new IllegalStateException(
                        "No EHR ID found for patient " + patientId + " on CDR '" + resolvedCdrName + "'"));

        // build fhirPath: /ResourceType + any query params except "patient"
        final String fhirPath = buildFhirPath(httpRequest, resourceType);
        log.debug("Resolved fhirPath={} for ehrId={}", fhirPath, ehrId);

        // step 1 — /openfhir/toaql
        final ToAqlResponse toAqlResponse = openFhirClient.getAql(new ToAqlRequest(null, ehrId, fhirPath), reqId);

        if (toAqlResponse.getAqls() == null || toAqlResponse.getAqls().isEmpty()) {
            log.debug("No AQLs returned for fhirPath={}", fhirPath);
            return emptySearchBundle();
        }

        // step 2 — execute AQLs against CDR
        final OpenEhrCdrClient cdrClient = openEhrCdrRegistry.resolve(cdrName);
        final List<JsonNode> allRows = new ArrayList<>();
        String templateId = null;
        for (final ToAqlResponse.AqlResponse aqlResponse : toAqlResponse.getAqls()) {
            if (aqlResponse.getType() == ToAqlResponse.AqlType.COMPOSITION
                    && toAqlResponse.getAqls().size() > 1) {
                continue;
            }
            final String openEhrResult = cdrClient.queryAql(aqlResponse.getAql());
            allRows.addAll(openEhrAqlUtil.extractArchetypeRows(openEhrResult));
            templateId = aqlResponse.getTemplateId();
        }

        if (allRows.isEmpty()) {
            return emptySearchBundle();
        }

        // step 3 — /openfhir/tofhir
        final String fhirJson = openFhirClient.toFhir(allRows, reqId, templateId);
        final Bundle resultBundle = fhirContext.newJsonParser().parseResource(Bundle.class, fhirJson);

        // filter to only resources matching the requested type, assign IDs where missing
        final Bundle searchBundle = emptySearchBundle();
        final ca.uhn.fhir.util.FhirTerser terser = fhirContext.newTerser();
        final String patientFullUrl = "Patient/" + patientId;
        resultBundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(r -> r != null && resourceType != null && resourceType.equals(r.getResourceType().name()))
                .forEach(r -> {
                    if (r.getIdElement().isEmpty()) {
                        r.setId(UUID.randomUUID().toString());
                    }
                    PatientReferenceInjector.injectPatientReference(terser, r, patientFullUrl);
                    searchBundle.addEntry().setResource(r);
                });

        searchBundle.setTotal(searchBundle.getEntry().size());
        return searchBundle;
    }

    /**
     * Returns the first configured rule whose {@code fhir-query} entries all match the request,
     * or {@code null} if none match. The special key {@code _resourceType} is matched against
     * the resource type extracted from the URI path rather than a query parameter.
     */
    private com.syntaric.InterceptorProperties.Rule resolveRule(final HttpServletRequest httpRequest,
                                                                final String resourceType) {
        for (final com.syntaric.InterceptorProperties.Rule rule : properties.getFhirQueryFilter().getRules()) {
            if (ruleMatches(rule, httpRequest, resourceType)) {
                return rule;
            }
        }
        return null;
    }

    boolean ruleMatches(final com.syntaric.InterceptorProperties.Rule rule,
                        final HttpServletRequest httpRequest,
                        final String resourceType) {
        for (final Map.Entry<String, String> criterion : rule.getFhirQuery().entrySet()) {
            final String expected = criterion.getValue();
            if (RESOURCE_TYPE_KEY.equals(criterion.getKey())) {
                if (resourceType == null) {
                    return false;
                }
                if (!WILDCARD.equals(expected) && !expected.equals(resourceType)) {
                    return false;
                }
            } else {
                final String paramValue = httpRequest.getParameter(criterion.getKey());
                if (paramValue == null) {
                    return false;
                }
                if (!WILDCARD.equals(expected) && !expected.equals(paramValue)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Extracts the last path segment if it looks like a FHIR resource type (starts with uppercase).
     */
    private String resourceTypeFromUri(final String uri) {
        final String[] segments = uri.split("/");
        if (segments.length == 0) {
            return null;
        }
        final String last = segments[segments.length - 1];
        return (last != null && !last.isBlank() && Character.isUpperCase(last.charAt(0))) ? last : null;
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

    private Bundle emptySearchBundle() {
        final Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(0);
        return bundle;
    }

    private static String jsonEscape(final String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
