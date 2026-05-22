package com.syntaric.hapi;

import ca.uhn.fhir.context.FhirContext;
import com.syntaric.PixManager;
import com.syntaric.openehr.Constants;
import com.syntaric.openehr.OpenEhrCdrClient;
import com.syntaric.openehr.OpenEhrCdrFileLoader;
import com.syntaric.openehr.OpenEhrCdrRegistry;
import com.syntaric.openfhir.OpenFhirClient;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Servlet filter that intercepts Bundle/Composition POSTs whose Composition profile
 * matches one of the configured {@link com.syntaric.InterceptorProperties.FhirCreateFilter#getInterceptedProfiles()},
 * forwards them to the OpenEHR CDR, and returns 201 + Location without storing in HAPI.
 * All other requests pass through unchanged.
 */
@Slf4j
@Component
public class FhirCreateFilter implements Filter {

    private static final String X_REQ_ID_HEADER = "x-req-id";

    private final FhirContext fhirContext;
    private final OpenFhirClient openFhirClient;
    private final OpenEhrCdrRegistry openEhrCdrRegistry;
    private final PixManager pixManager;
    private final com.syntaric.InterceptorProperties properties;

    public FhirCreateFilter(final FhirContext fhirContext,
                            final OpenFhirClient openFhirClient,
                            final OpenEhrCdrRegistry openEhrCdrRegistry,
                            final PixManager pixManager,
                            final com.syntaric.InterceptorProperties properties) {
        this.fhirContext = fhirContext;
        this.openFhirClient = openFhirClient;
        this.openEhrCdrRegistry = openEhrCdrRegistry;
        this.pixManager = pixManager;
        this.properties = properties;
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response,
                         final FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!HttpMethod.POST.name().equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        final String cdrName = ((HttpServletRequest) request).getHeader(OpenEhrCdrRegistry.TARGET_CDR_HEADER);
        if ("fhir".equalsIgnoreCase(cdrName)) {
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

        final String matchedProfile = resolveMatchedProfile(resource);
        if (matchedProfile == null) {
            log.info("POST {} — no intercepted profile matched, passing through to HAPI", httpRequest.getRequestURI());
            chain.doFilter(cachedRequest, response);
            return;
        }

        log.info("POST {} — profile '{}' matched, forwarding to OpenEHR CDR (skipping HAPI storage)",
                httpRequest.getRequestURI(), matchedProfile);

        final String incomingReqId = httpRequest.getHeader(X_REQ_ID_HEADER);
        final String reqId = (incomingReqId != null && !incomingReqId.isBlank()) ? incomingReqId : UUID.randomUUID().toString();

        try {
            final String location = forwardToOpenEhr(resource, httpRequest, reqId);
            httpResponse.setStatus(HttpServletResponse.SC_CREATED);
            if (location != null) {
                httpResponse.setHeader("Location", location);
            }
        } catch (final Exception e) {
            log.error("Failed to forward resource to OpenEHR CDR", e);
            httpResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write(buildErrorJson(e));
        }
        httpResponse.flushBuffer();
        // do NOT call chain.doFilter — request is fully handled
    }

    private String forwardToOpenEhr(final IBaseResource resource, final HttpServletRequest servletRequest, final String reqId) {
        final String cdrName = servletRequest.getHeader(OpenEhrCdrRegistry.TARGET_CDR_HEADER);
        final OpenEhrCdrFileLoader.CdrEntry cdrEntry = openEhrCdrRegistry.resolveEntry(cdrName);
        final String resolvedCdrName = cdrEntry.getId();
        final OpenEhrCdrClient cdrClient = new OpenEhrCdrClient(cdrEntry.getBaseUrl(), cdrEntry.getOauth2(), cdrEntry.getBasicAuth());

        final String patientId = extractPatientReferenceIdPart(resource);
        if (patientId == null) {
            throw new IllegalStateException("Could not extract patient reference from a resource " + resource.getIdElement());
        }

        final String ehrId;
        try {
            ehrId = pixManager.resolveById(patientId, Constants.EHRID_SYSTEM, resolvedCdrName)
                    .orElseGet(() -> {
                        log.info("No EHR ID found for patient {} on CDR '{}', provisioning now", patientId, resolvedCdrName);
                        return pixManager.provisionEhrForPatient(patientId, cdrClient, resolvedCdrName).getValue();
                    });
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "Failed to resolve/provision EHR ID for patient " + patientId + " on CDR '" + resolvedCdrName + "': " + e.getMessage(), e);
        }

        final String openEhrPayload = openFhirClient.convert(toBundle(resource), reqId);

        final String location = cdrClient.store(openEhrPayload, ehrId);
        log.info("Resource forwarded to OpenEHR CDR '{}', location={}, patient={}",
                resolvedCdrName, location, ehrId);
        return location;
    }

    private String resolveMatchedProfile(final IBaseResource resource) {
        if (resource instanceof Resource r) {
            final String direct = matchedProfile(r);
            if (direct != null) {
                return direct;
            }
        }
        if (resource instanceof Bundle bundle) {
            return bundle.getEntry().stream()
                    .map(Bundle.BundleEntryComponent::getResource)
                    .filter(Objects::nonNull)
                    .map(this::matchedProfile)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private String matchedProfile(final Resource resource) {
        return resource.getMeta().getProfile().stream()
                .map(p -> p.getValue())
                .filter(properties.getFhirCreateFilter().getInterceptedProfiles()::contains)
                .findFirst()
                .orElse(null);
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

    // Per-resource-type FHIRPath expressions that may hold a patient reference,
    // tried in order until one resolves to a non-empty Patient reference.
    private static final Map<String, List<String>> PATIENT_PATHS = Map.ofEntries(
            Map.entry("Account",                      List.of("Account.subject")),
            Map.entry("AdverseEvent",                 List.of("AdverseEvent.subject")),
            Map.entry("AllergyIntolerance",           List.of("AllergyIntolerance.patient", "AllergyIntolerance.recorder", "AllergyIntolerance.asserter")),
            Map.entry("Appointment",                  List.of("Appointment.participant.actor")),
            Map.entry("AppointmentResponse",          List.of("AppointmentResponse.actor")),
            Map.entry("AuditEvent",                   List.of("AuditEvent.patient", "AuditEvent.agent.who", "AuditEvent.entity.what")),
            Map.entry("Basic",                        List.of("Basic.subject", "Basic.author")),
            Map.entry("BodyStructure",                List.of("BodyStructure.patient")),
            Map.entry("CarePlan",                     List.of("CarePlan.subject", "CarePlan.activity.detail.performer")),
            Map.entry("CareTeam",                     List.of("CareTeam.subject", "CareTeam.participant.member")),
            Map.entry("ChargeItem",                   List.of("ChargeItem.subject")),
            Map.entry("Claim",                        List.of("Claim.patient", "Claim.payee.party")),
            Map.entry("ClaimResponse",                List.of("ClaimResponse.patient")),
            Map.entry("ClinicalImpression",           List.of("ClinicalImpression.subject")),
            Map.entry("Communication",                List.of("Communication.subject", "Communication.sender", "Communication.recipient")),
            Map.entry("CommunicationRequest",         List.of("CommunicationRequest.subject", "CommunicationRequest.sender", "CommunicationRequest.recipient", "CommunicationRequest.requester")),
            Map.entry("Composition",                  List.of("Composition.subject", "Composition.author", "Composition.attester.party")),
            Map.entry("Condition",                    List.of("Condition.subject", "Condition.asserter")),
            Map.entry("Consent",                      List.of("Consent.patient")),
            Map.entry("Coverage",                     List.of("Coverage.policyHolder", "Coverage.subscriber", "Coverage.beneficiary", "Coverage.payor")),
            Map.entry("CoverageEligibilityRequest",   List.of("CoverageEligibilityRequest.patient")),
            Map.entry("CoverageEligibilityResponse",  List.of("CoverageEligibilityResponse.patient")),
            Map.entry("DetectedIssue",                List.of("DetectedIssue.patient")),
            Map.entry("DeviceRequest",                List.of("DeviceRequest.subject", "DeviceRequest.performer")),
            Map.entry("DeviceUseStatement",           List.of("DeviceUseStatement.subject")),
            Map.entry("DiagnosticReport",             List.of("DiagnosticReport.subject")),
            Map.entry("DocumentManifest",             List.of("DocumentManifest.subject", "DocumentManifest.author", "DocumentManifest.recipient")),
            Map.entry("DocumentReference",            List.of("DocumentReference.subject", "DocumentReference.author")),
            Map.entry("Encounter",                    List.of("Encounter.subject")),
            Map.entry("EnrollmentRequest",            List.of("EnrollmentRequest.candidate")),
            Map.entry("EpisodeOfCare",                List.of("EpisodeOfCare.patient")),
            Map.entry("ExplanationOfBenefit",         List.of("ExplanationOfBenefit.patient", "ExplanationOfBenefit.payee.party")),
            Map.entry("FamilyMemberHistory",          List.of("FamilyMemberHistory.patient")),
            Map.entry("Flag",                         List.of("Flag.subject")),
            Map.entry("Goal",                         List.of("Goal.subject")),
            Map.entry("Group",                        List.of("Group.member.entity")),
            Map.entry("ImagingStudy",                 List.of("ImagingStudy.subject")),
            Map.entry("Immunization",                 List.of("Immunization.patient")),
            Map.entry("ImmunizationEvaluation",       List.of("ImmunizationEvaluation.patient")),
            Map.entry("ImmunizationRecommendation",   List.of("ImmunizationRecommendation.patient")),
            Map.entry("Invoice",                      List.of("Invoice.subject", "Invoice.patient", "Invoice.recipient")),
            Map.entry("List",                         List.of("List.subject", "List.source")),
            Map.entry("MeasureReport",                List.of("MeasureReport.subject")),
            Map.entry("Media",                        List.of("Media.subject")),
            Map.entry("MedicationAdministration",     List.of("MedicationAdministration.patient", "MedicationAdministration.subject", "MedicationAdministration.performer.actor")),
            Map.entry("MedicationDispense",           List.of("MedicationDispense.subject", "MedicationDispense.patient", "MedicationDispense.receiver")),
            Map.entry("MedicationRequest",            List.of("MedicationRequest.subject")),
            Map.entry("MedicationStatement",          List.of("MedicationStatement.subject")),
            Map.entry("MolecularSequence",            List.of("MolecularSequence.patient")),
            Map.entry("NutritionOrder",               List.of("NutritionOrder.patient")),
            Map.entry("Observation",                  List.of("Observation.subject", "Observation.performer")),
            Map.entry("Patient",                      List.of("Patient.link.other")),
            Map.entry("Person",                       List.of("Person.link.target")),
            Map.entry("Procedure",                    List.of("Procedure.subject", "Procedure.performer.actor")),
            Map.entry("Provenance",                   List.of("Provenance.patient")),
            Map.entry("QuestionnaireResponse",        List.of("QuestionnaireResponse.subject", "QuestionnaireResponse.author")),
            Map.entry("RelatedPerson",                List.of("RelatedPerson.patient")),
            Map.entry("RequestGroup",                 List.of("RequestGroup.subject", "RequestGroup.participant")),
            Map.entry("ResearchSubject",              List.of("ResearchSubject.individual")),
            Map.entry("RiskAssessment",               List.of("RiskAssessment.subject")),
            Map.entry("Schedule",                     List.of("Schedule.actor")),
            Map.entry("ServiceRequest",               List.of("ServiceRequest.subject", "ServiceRequest.performer")),
            Map.entry("Specimen",                     List.of("Specimen.subject")),
            Map.entry("SupplyDelivery",               List.of("SupplyDelivery.patient")),
            Map.entry("SupplyRequest",                List.of("SupplyRequest.subject")),
            Map.entry("VisionPrescription",           List.of("VisionPrescription.patient"))
    );

    private String extractPatientReferenceIdPart(final IBaseResource resource) {
        if (resource instanceof Bundle bundle) {
            return bundle.getEntry().stream()
                    .map(Bundle.BundleEntryComponent::getResource)
                    .filter(Objects::nonNull)
                    .map(this::extractPatientReferenceIdPart)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        final String resourceType = resource.fhirType();
        final List<String> paths = PATIENT_PATHS.get(resourceType);
        if (paths == null) {
            log.warn("No patient path defined for resource type '{}', cannot extract patient reference", resourceType);
            return null;
        }

        final ca.uhn.fhir.util.FhirTerser terser = fhirContext.newTerser();
        for (final String path : paths) {
            try {
                final List<IBaseReference> refs = terser.getValues(resource, path, IBaseReference.class);
                final String patientId = refs.stream()
                        .filter(ref -> ref != null && !ref.isEmpty())
                        .filter(ref -> {
                            final String refType = ref.getReferenceElement().getResourceType();
                            return refType == null || "Patient".equals(refType);
                        })
                        .map(ref -> ref.getReferenceElement().getIdPart())
                        .filter(id -> id != null && !id.isBlank())
                        .findFirst()
                        .orElse(null);
                if (patientId != null) {
                    return patientId;
                }
            } catch (final Exception e) {
                log.debug("Could not resolve path '{}' on {}: {}", path, resourceType, e.getMessage());
            }
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
        return "{\"error\":\"" + message + "\",\"cause\":\"" + causeChain + "\"}";
    }

    private static String jsonEscape(final String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
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
