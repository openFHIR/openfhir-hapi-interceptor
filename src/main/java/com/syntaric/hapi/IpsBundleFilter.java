package com.syntaric.hapi;

import ca.uhn.fhir.context.FhirContext;
import com.syntaric.PixManager;
import com.syntaric.ips.IpsAuditRecorder;
import com.syntaric.openehr.OpenEhrCdrClient;
import com.syntaric.openehr.OpenEhrCdrException;
import com.syntaric.openehr.OpenEhrCdrRegistry;
import com.syntaric.openfhir.OpenFhirClient;
import com.syntaric.openfhir.OpenFhirException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Servlet filter that intercepts IPS Bundle/Composition POSTs before they reach HAPI,
 * forwards them to the OpenEHR CDR, and returns 201 + Location without storing in HAPI.
 * Non-IPS requests pass through unchanged.
 */
@Slf4j
@Configuration
public class IpsBundleFilter implements Filter {

    private static final String IPS_COMPOSITION_PROFILE =
            "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips";

    private static final String X_REQ_ID_HEADER = "x-req-id";

    private final FhirContext fhirContext;
    private final OpenFhirClient openFhirClient;
    private final OpenEhrCdrRegistry openEhrCdrRegistry;
    private final PixManager pixManager;
    private final IpsAuditRecorder auditRecorder;

    public IpsBundleFilter(final FhirContext fhirContext,
                           final OpenFhirClient openFhirClient,
                           final OpenEhrCdrRegistry openEhrCdrRegistry,
                           final PixManager pixManager,
                           final IpsAuditRecorder auditRecorder) {
        this.fhirContext = fhirContext;
        this.openFhirClient = openFhirClient;
        this.openEhrCdrRegistry = openEhrCdrRegistry;
        this.pixManager = pixManager;
        this.auditRecorder = auditRecorder;
    }

    @Bean
    public FilterRegistrationBean<IpsBundleFilter> ipsFilterRegistration() {
        final FilterRegistrationBean<IpsBundleFilter> registration = new FilterRegistrationBean<>(this);
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response,
                         final FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!"POST".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // cheap path check — only Bundle and Composition POSTs can be IPS
        final String path = httpRequest.getRequestURI();
        if (!path.endsWith("/fhir") && !path.endsWith("/Composition")) {
            chain.doFilter(request, response);
            return;
        }

        // buffer the body so we can read it here and still let HAPI read it if needed
        final CachedBodyRequest cachedRequest = new CachedBodyRequest(httpRequest);
        final String body = cachedRequest.getBodyAsString();

        final IBaseResource resource;
        try {
            final String contentType = httpRequest.getContentType();
            resource = (contentType != null && contentType.contains("xml"))
                    ? fhirContext.newXmlParser().parseResource(body)
                    : fhirContext.newJsonParser().parseResource(body);
        } catch (final Exception e) {
            chain.doFilter(cachedRequest, response);
            return;
        }

        if (!isIpsResource(resource)) {
            chain.doFilter(cachedRequest, response);
            return;
        }

        log.info("IPS resource detected on POST ({}), forwarding to OpenEHR CDR — skipping HAPI storage",
                resource.getClass().getSimpleName());

        final String incomingReqId = httpRequest.getHeader(X_REQ_ID_HEADER);
        final String reqId = (incomingReqId != null && !incomingReqId.isBlank()) ? incomingReqId : UUID.randomUUID().toString();

        auditRecorder.record(reqId, 1, "step-1-post-bundle",
                "POST " + httpRequest.getRequestURI() + " — " + resource.getClass().getSimpleName(),
                body,
                null,
                DeviceRequest.RequestIntent.FILLERORDER);

        try {
            final String location = forwardToOpenEhr(resource, httpRequest, reqId);
            httpResponse.setStatus(HttpServletResponse.SC_CREATED);
            if (location != null) {
                httpResponse.setHeader("Location", location);
            }
        } catch (final Exception e) {
            log.error("Failed to forward IPS resource to OpenEHR CDR", e);
            httpResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write(buildErrorJson(e));
        }
        httpResponse.flushBuffer();
        // do NOT call chain.doFilter — request is fully handled
    }

    private String forwardToOpenEhr(final IBaseResource resource, final HttpServletRequest servletRequest, final String reqId) {
        final String cdrName = servletRequest.getHeader(OpenEhrCdrRegistry.TARGET_CDR_HEADER);
        final String resolvedCdrName = openEhrCdrRegistry.resolveName(cdrName);
        final OpenEhrCdrClient cdrClient = openEhrCdrRegistry.resolve(cdrName);

        final String patientId = extractPatientReferenceIdPart(resource);
        if (patientId == null) {
            throw new IllegalStateException("Could not extract patient reference from IPS resource " + resource.getIdElement());
        }

        final String ehrId;
        try {
            ehrId = pixManager.resolveById(patientId, IpsBundleInterceptor.EHRID_SYSTEM, resolvedCdrName)
                    .orElseGet(() -> {
                        log.info("No EHR ID found for patient {} on CDR '{}', provisioning now", patientId, resolvedCdrName);
                        return pixManager.provisionEhrForPatient(patientId, cdrClient, resolvedCdrName);
                    });
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "Failed to resolve/provision EHR ID for patient " + patientId + " on CDR '" + resolvedCdrName + "': " + e.getMessage(), e);
        }

        final String fhirBundleJson = fhirContext.newJsonParser().encodeResourceToString(toBundle(resource));
        final String openEhrPayload;
        try {
            openEhrPayload = openFhirClient.convert(toBundle(resource), reqId);
        } catch (final OpenFhirException e) {
            auditRecorder.recordError(reqId, 2, "step-2-toopenehr",
                    fhirBundleJson,
                    e.toDetailString(),
                    reqId,
                    DeviceRequest.RequestIntent.FILLERORDER);
            throw e;
        }
        auditRecorder.record(reqId, 2, "step-2-toopenehr",
                fhirBundleJson,
                openEhrPayload,
                reqId,
                DeviceRequest.RequestIntent.FILLERORDER);

        try {
            final String location = cdrClient.store(openEhrPayload, ehrId);
            log.info("IPS resource forwarded to OpenEHR CDR '{}', location={}, patient={}",
                    resolvedCdrName, location, ehrId);
            auditRecorder.record(reqId, 3, "step-3-cdr-store",
                    "ehrId=" + ehrId + ", cdr=" + resolvedCdrName + "***REMOVED***n" + openEhrPayload,
                    "Location: " + location,
                    null,
                    DeviceRequest.RequestIntent.FILLERORDER);
            return location;
        } catch (final OpenEhrCdrException e) {
            auditRecorder.recordError(reqId, 3, "step-3-cdr-store",
                    "ehrId=" + ehrId + ", cdr=" + resolvedCdrName + "***REMOVED***n" + openEhrPayload,
                    e.toDetailString(),
                    null,
                    DeviceRequest.RequestIntent.FILLERORDER);
            throw e;
        }
    }

    private boolean isIpsResource(final IBaseResource resource) {
        if (resource instanceof Composition composition) {
            return hasIpsProfile(composition);
        }
        if (resource instanceof Bundle bundle) {
            return isIpsBundle(bundle);
        }
        return false;
    }

    private boolean isIpsBundle(final Bundle bundle) {
        return bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(r -> r instanceof Composition)
                .map(r -> (Composition) r)
                .anyMatch(this::hasIpsProfile);
    }

    private boolean hasIpsProfile(final Composition composition) {
        return composition.getMeta().getProfile().stream()
                .anyMatch(profile -> IPS_COMPOSITION_PROFILE.equals(profile.getValue()));
    }

    private Bundle toBundle(final IBaseResource iBaseResource) {
        if(iBaseResource instanceof Bundle) {
            return (Bundle) iBaseResource;
        }
        final Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);
        bundle.addEntry(new Bundle.BundleEntryComponent().setResource((Resource) iBaseResource));
        return bundle;
    }

    private String extractPatientReferenceIdPart(final IBaseResource resource) {
        if (resource instanceof Composition composition) {
            return composition.getSubject().getReferenceElement().getIdPart();
        }
        if (resource instanceof Bundle bundle) {
            return bundle.getEntry().stream()
                    .map(Bundle.BundleEntryComponent::getResource)
                    .filter(r -> r instanceof Composition)
                    .map(r -> (Composition) r)
                    .findFirst()
                    .map(c -> c.getSubject().getReferenceElement().getIdPart())
                    .orElse(null);
        }
        return null;
    }

    private static String buildErrorJson(final Throwable e) {
        final StringBuilder causes = new StringBuilder();
        Throwable cause = e.getCause();
        while (cause != null) {
            if (!causes.isEmpty()) {
                causes.append(" -> ");
            }
            causes.append(cause.getMessage());
            cause = cause.getCause();
        }

        final String message = jsonEscape(e.getMessage());
        final String causeChain = jsonEscape(causes.toString());
        return "{***REMOVED***"error***REMOVED***":***REMOVED***"" + message + "***REMOVED***",***REMOVED***"cause***REMOVED***":***REMOVED***"" + causeChain + "***REMOVED***"}";
    }

    private static String jsonEscape(final String s) {
        if (s == null) return "";
        return s.replace("***REMOVED******REMOVED***", "***REMOVED******REMOVED******REMOVED******REMOVED***").replace("***REMOVED***"", "***REMOVED******REMOVED******REMOVED***"").replace("***REMOVED***n", "***REMOVED******REMOVED***n").replace("***REMOVED***r", "***REMOVED******REMOVED***r");
    }

    // -------------------------------------------------------------------------
    // Body-caching request wrapper
    // -------------------------------------------------------------------------

    private static class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        CachedBodyRequest(final HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = request.getInputStream().readAllBytes();
        }

        String getBodyAsString() {
            return new String(cachedBody, StandardCharsets.UTF_8);
        }

        @Override
        public ServletInputStream getInputStream() {
            final ByteArrayInputStream stream = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override public int read() { return stream.read(); }
                @Override public boolean isFinished() { return stream.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(final ReadListener listener) {}
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
