package com.syntaric.ips;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.syntaric.InterceptorProperties;
import com.syntaric.PixManager;
import com.syntaric.openehr.Constants;
import com.syntaric.openehr.OpenEhrAqlUtil;
import com.syntaric.openehr.OpenEhrCdrClient;
import com.syntaric.openehr.OpenEhrCdrRegistry;
import com.syntaric.openfhir.OpenFhirClient;
import com.syntaric.openfhir.aql.ToAqlRequest;
import com.syntaric.openfhir.aql.ToAqlResponse;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class IpsProvider {

    private static final String X_REQ_ID_HEADER = "x-req-id";
    private static final List<String> FHIR_PATHS = List.of("/AllergyIntolerance",
                                                           "/Condition?verification-status=confirmed",
                                                           "/MedicationStatement?_include=MedicationStatement:medication",
                                                           "/MedicationRequest?_include=MedicationRequest:medication",
                                                           "/Immunization",
                                                           "/Procedure",
                                                           "/DiagnosticReport?_include=DiagnosticReport:result",
                                                           "/Observation?category=laboratory",
                                                           "/Observation?category=vital-signs",
                                                           "/DeviceUseStatement?_include=DeviceUseStatement:device",
                                                           "/Specimen");

    private final DaoRegistry daoRegistry;
    private final PixManager pixManager;
    private final OpenEhrCdrRegistry openEhrCdrRegistry;
    private final OpenFhirClient openFhirClient;
    private final OpenEhrAqlUtil openEhrAqlUtil;
    private final FhirContext fhirContext;
    private final InterceptorProperties interceptorProperties;

    public IpsProvider(final DaoRegistry daoRegistry,
                       final PixManager pixManager,
                       final OpenEhrCdrRegistry openEhrCdrRegistry,
                       final OpenFhirClient openFhirClient,
                       final OpenEhrAqlUtil openEhrAqlUtil,
                       final FhirContext fhirContext,
                       final InterceptorProperties interceptorProperties) {
        this.daoRegistry = daoRegistry;
        this.pixManager = pixManager;
        this.openEhrCdrRegistry = openEhrCdrRegistry;
        this.openFhirClient = openFhirClient;
        this.openEhrAqlUtil = openEhrAqlUtil;
        this.fhirContext = fhirContext;
        this.interceptorProperties = interceptorProperties;
    }

    @Operation(name = "$summary", idempotent = true, type = Patient.class)
    public Bundle patientSummary(
            @IdParam final IdType patientId,
            final RequestDetails requestDetails) {

        final String patientIdPart = patientId.getIdPart();
        final String incomingReqId = requestDetails.getHeader(X_REQ_ID_HEADER);
        final String reqId = (incomingReqId != null && !incomingReqId.isBlank()) ? incomingReqId : UUID.randomUUID().toString();
        log.info("$summary called for patient {}, reqId={}", patientIdPart, reqId);

        // (1) get patient
        final Patient patient = daoRegistry.<Patient>getResourceDao(Patient.class)
                .read(patientId, new SystemRequestDetails());

        // (2) fork on cdr name
        final String cdrName = requestDetails.getHeader(OpenEhrCdrRegistry.TARGET_CDR_HEADER);

        // (3) resolve EHR ID
        final String resolvedCdrName = openEhrCdrRegistry.resolveName(cdrName);
        final String ehrId = pixManager.resolveById(patientIdPart, Constants.EHRID_SYSTEM, resolvedCdrName)
                .orElseThrow(() -> new IllegalStateException(
                        "No EHR ID found for patient " + patientIdPart + " on CDR '" + resolvedCdrName + "'"));

        // (4) /openfhir/toaql — get AQLs for each fhir path
        final OpenEhrCdrClient cdrClient = openEhrCdrRegistry.resolve(cdrName);
        final List<JsonNode> allRows = new ArrayList<>();

        for (final String fhirPath : FHIR_PATHS) {
            final ToAqlRequest toAqlRequest = new ToAqlRequest(interceptorProperties.getIps().getTemplateId(), ehrId, fhirPath);

            final ToAqlResponse toAqlResponse = openFhirClient.getAql(toAqlRequest, reqId);
            if (toAqlResponse.getAqls() == null || toAqlResponse.getAqls().isEmpty()) {
                log.debug("No AQLs returned for fhirPath={}, skipping", fhirPath);
                continue;
            }

            // (5) trigger AQLs against CDR
            for (final ToAqlResponse.AqlResponse aqlResponse : toAqlResponse.getAqls()) {
                if (aqlResponse.getType() == ToAqlResponse.AqlType.COMPOSITION) {
                    continue;
                }
                final String aql = aqlResponse.getAql();
                final String openEhrResult = cdrClient.queryAql(aql);
                allRows.addAll(openEhrAqlUtil.extractArchetypeRows(openEhrResult));
            }
        }

        if (allRows.isEmpty()) {
            log.info("No archetype rows found for patient {}, returning empty IPS bundle", patientIdPart);
            return buildEmptyBundle(patient);
        }

        // (6) /openfhir/tofhir
        log.info("Sending {} archetype rows to toFhir for patient {}", allRows.size(), patientIdPart);
        final String fhirJson = openFhirClient.toFhir(allRows, reqId, interceptorProperties.getIps().getTemplateId());

        final Bundle bundle = fhirContext.newJsonParser().parseResource(Bundle.class, fhirJson);
        injectPatient(bundle, patient);
        bundle.getEntryFirstRep().setFullUrl("urn:uuid:" + UUID.randomUUID());

        return bundle;
    }



    private Bundle buildEmptyBundle(final Patient patient) {
        final String patientUuid = UUID.randomUUID().toString();
        final String patientFullUrl = "urn:uuid:" + patientUuid;
        patient.setId(patientUuid);

        final Composition composition = new Composition();
        composition.setId(UUID.randomUUID().toString());
        composition.getMeta().addProfile("http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips");
        composition.setStatus(Composition.CompositionStatus.FINAL);
        composition.setType(new CodeableConcept(new Coding("http://loinc.org", "60591-5", "Patient summary Document")));
        composition.setSubject(new Reference(patientFullUrl));
        composition.setDateElement(new DateTimeType(new Date()));
        composition.addAuthor().setDisplay("openFHIR");
        composition.setTitle("Patient Summary");

        composition.addSection(emptySection("Active Problems", "http://loinc.org", "11450-4", "Problem list Reported"));
        composition.addSection(emptySection("Active Allergies and Intolerances", "http://loinc.org", "48765-2", "Allergies and Intolerances"));
        composition.addSection(emptySection("Active Medication List", "http://loinc.org", "10160-0", "Medication List"));

        final Bundle bundle = new Bundle();
        bundle.getMeta().addProfile("http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips");
        bundle.setType(Bundle.BundleType.DOCUMENT);
        bundle.setTimestamp(new Date());
        bundle.setIdentifier(new Identifier().setSystem("urn:oid:2.16.840.1.113883.3.72").setValue(UUID.randomUUID().toString()));
        bundle.addEntry().setFullUrl("urn:uuid:" + composition.getId()).setResource(composition);
        bundle.addEntry().setFullUrl(patientFullUrl).setResource(patient);
        return bundle;
    }

    private Composition.SectionComponent emptySection(final String title,
                                                      final String system,
                                                      final String code,
                                                      final String display) {
        final Composition.SectionComponent section = new Composition.SectionComponent();
        section.setTitle(title);
        section.setCode(new CodeableConcept(new Coding(system, code, display)));
        final Narrative text = new Narrative();
        text.setStatusAsString("generated");
        text.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\">" + title + "</div>");
        section.setText(text);
        section.setEmptyReason(new CodeableConcept(
                new Coding("http://terminology.hl7.org/CodeSystem/list-empty-reason", "unavailable", null)));
        return section;
    }

    private void injectPatient(final Bundle bundle, final Patient patient) {
        final String patientUuid = UUID.randomUUID().toString();
        final String patientFullUrl = "urn:uuid:" + patientUuid;
        patient.setId(patientUuid);

        // add Patient entry at position 1 (after Composition)
        final Bundle.BundleEntryComponent patientEntry = new Bundle.BundleEntryComponent();
        patientEntry.setResource(patient);
        patientEntry.setFullUrl(patientFullUrl);
        bundle.getEntry().add(1, patientEntry);

        // update subject/patient references in all clinical resources to the urn:uuid: fullUrl
        for (final Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            final var resource = entry.getResource();
            if (resource instanceof Composition composition) {
                composition.setSubject(new Reference(patientFullUrl));
            } else if (resource instanceof Condition condition) {
                condition.setSubject(new Reference(patientFullUrl));
            } else if (resource instanceof AllergyIntolerance allergyIntolerance) {
                allergyIntolerance.setPatient(new Reference(patientFullUrl));
            }
        }
    }
}
