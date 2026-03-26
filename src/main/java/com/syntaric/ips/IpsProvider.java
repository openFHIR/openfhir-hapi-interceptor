package com.syntaric.ips;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.syntaric.PixManager;
import com.syntaric.hapi.IpsBundleInterceptor;
import com.syntaric.openehr.OpenEhrAqlUtil;
import com.syntaric.openehr.OpenEhrCdrClient;
import com.syntaric.openehr.OpenEhrCdrException;
import com.syntaric.openehr.OpenEhrCdrRegistry;
import com.syntaric.openfhir.OpenFhirClient;
import com.syntaric.openfhir.OpenFhirException;
import com.syntaric.openfhir.aql.ToAqlRequest;
import com.syntaric.openfhir.aql.ToAqlResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class IpsProvider {

    private static final String TEMPLATE_ID = "International Patient Summary";
    private static final String X_REQ_ID_HEADER = "x-req-id";
    private static final List<String> FHIR_PATHS = List.of("/AllergyIntolerance", "/Condition?verification-status=confirmed");

    private final DaoRegistry daoRegistry;
    private final PixManager pixManager;
    private final OpenEhrCdrRegistry openEhrCdrRegistry;
    private final OpenFhirClient openFhirClient;
    private final OpenEhrAqlUtil openEhrAqlUtil;
    private final IpsAuditRecorder auditRecorder;

    public IpsProvider(final DaoRegistry daoRegistry,
                       final PixManager pixManager,
                       final OpenEhrCdrRegistry openEhrCdrRegistry,
                       final OpenFhirClient openFhirClient,
                       final OpenEhrAqlUtil openEhrAqlUtil,
                       final IpsAuditRecorder auditRecorder) {
        this.daoRegistry = daoRegistry;
        this.pixManager = pixManager;
        this.openEhrCdrRegistry = openEhrCdrRegistry;
        this.openFhirClient = openFhirClient;
        this.openEhrAqlUtil = openEhrAqlUtil;
        this.auditRecorder = auditRecorder;
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
        if ("fhir".equalsIgnoreCase(cdrName) || StringUtils.isEmpty(cdrName)) {
            log.info("CDR is 'fhir', using local FHIR DB for patient {}", patientIdPart);
            final Bundle result = fhirSummary(patientId, patient);
            auditRecorder.record(reqId, 1, "step-1-summary",
                    "GET Patient/" + patientIdPart + "/$summary (cdr=fhir)",
                    "IPS Bundle with " + result.getEntry().size() + " entries",
                    null);
            return result;
        }

        // (3) resolve EHR ID
        final String resolvedCdrName = openEhrCdrRegistry.resolveName(cdrName);
        final String ehrId = pixManager.resolveById(patientIdPart, IpsBundleInterceptor.EHRID_SYSTEM, resolvedCdrName)
                .orElseThrow(() -> new IllegalStateException(
                        "No EHR ID found for patient " + patientIdPart + " on CDR '" + resolvedCdrName + "'"));

        // (4) /openfhir/toaql — get AQLs for each fhir path
        final OpenEhrCdrClient cdrClient = openEhrCdrRegistry.resolve(cdrName);
        final List<JsonNode> allRows = new ArrayList<>();
        final StringBuilder toAqlReqText = new StringBuilder();
        final StringBuilder toAqlRespText = new StringBuilder();

        for (final String fhirPath : FHIR_PATHS) {
            final ToAqlRequest toAqlRequest = new ToAqlRequest(TEMPLATE_ID, ehrId, fhirPath);
            toAqlReqText.append("templateId=").append(TEMPLATE_ID)
                    .append(", ehrId=").append(ehrId)
                    .append(", fhirPath=").append(fhirPath).append("***REMOVED***n");

            final ToAqlResponse toAqlResponse;
            try {
                toAqlResponse = openFhirClient.getAql(toAqlRequest, reqId);
            } catch (final OpenFhirException e) {
                auditRecorder.recordError(reqId, 2, "step-2-toaql",
                        toAqlReqText.toString().trim(),
                        e.toDetailString(),
                        reqId,
                        DeviceRequest.RequestIntent.PLAN);
                throw e;
            }
            if (toAqlResponse.getAqls() == null || toAqlResponse.getAqls().isEmpty()) {
                log.debug("No AQLs returned for fhirPath={}, skipping", fhirPath);
                toAqlRespText.append("fhirPath=").append(fhirPath).append(": no AQLs returned***REMOVED***n");
                continue;
            }
            for (final ToAqlResponse.AqlResponse aql : toAqlResponse.getAqls()) {
                toAqlRespText.append("type=").append(aql.getType())
                        .append(", aql=").append(aql.getAql()).append("***REMOVED***n");
            }

            // (5) trigger AQLs against CDR
            final StringBuilder aqlReqText = new StringBuilder();
            final StringBuilder aqlRespText = new StringBuilder();

            for (final ToAqlResponse.AqlResponse aqlResponse : toAqlResponse.getAqls()) {
                if (aqlResponse.getType() == ToAqlResponse.AqlType.COMPOSITION) {
                    continue;
                }
                aqlReqText.append(aqlResponse.getAql()).append("***REMOVED***n");
                final String openEhrResult;
                try {
                    String aql = null;
                    try {
                        aql = aqlResponse.getAql();
                        if(cdrName.contains("cadasto")) {
                            aql = aql.replace("FROM EHR e", "FROM EHR e  CONTAINS COMPOSITION c");
                        }
                    } catch (Exception e) {
                        log.warn("Exception trying to add contains composition e to cadasto aql {}", e.getMessage());
                    }
                    openEhrResult = cdrClient.queryAql(aql);
                } catch (final OpenEhrCdrException e) {
                    auditRecorder.recordError(reqId, 3, "step-3-trigger-aql-" + fhirPath,
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
                auditRecorder.record(reqId, 3, "step-3-trigger-aql-" + fhirPath,
                        aqlReqText.toString().trim(),
                        aqlRespText.toString().trim(),
                        null);
            }
        }

        auditRecorder.record(reqId, 2, "step-2-toaql",
                toAqlReqText.toString().trim(),
                toAqlRespText.toString().trim(),
                reqId);

        if (allRows.isEmpty()) {
            log.info("No archetype rows found for patient {}, returning empty IPS bundle", patientIdPart);
            final Bundle empty = buildEmptyBundle(patient);
            auditRecorder.record(reqId, 1, "step-1-summary",
                    "GET Patient/" + patientIdPart + "/$summary (cdr=" + cdrName + ")",
                    "Empty IPS Bundle (no archetype rows found)",
                    null);
            return empty;
        }

        // (6) /openfhir/tofhir
        log.info("Sending {} archetype rows to toFhir for patient {}", allRows.size(), patientIdPart);
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


        final Bundle bundle = openFhirClient.getFhirContext().newJsonParser().parseResource(Bundle.class, fhirJson);
        injectPatient(bundle, patient);
        bundle.getEntryFirstRep().setFullUrl("urn:uuid:" + UUID.randomUUID());

        auditRecorder.record(reqId, 4, "step-4-tofhir",
                toFhirReqText,
                FhirContext.forR4Cached().newJsonParser().encodeResourceToString(bundle),
                reqId);

        auditRecorder.record(reqId, 1, "step-1-summary",
                "GET Patient/" + patientIdPart + "/$summary (cdr=" + cdrName + ")",
                "IPS Bundle with " + bundle.getEntry().size() + " entries",
                null);

        return bundle;
    }

    private Bundle fhirSummary(final IdType patientId, final Patient patient) {
        final String patientRef = "Patient/" + patientId.getIdPart();

        final SearchParameterMap params = new SearchParameterMap();
        params.add(AllergyIntolerance.SP_PATIENT, new ReferenceParam(patientRef));
        final List<IBaseResource> allergies = daoRegistry.getResourceDao(AllergyIntolerance.class)
                .search(params, new SystemRequestDetails()).getAllResources();

        final SearchParameterMap conditionParams = new SearchParameterMap();
        conditionParams.add(Condition.SP_PATIENT, new ReferenceParam(patientRef));
        final List<IBaseResource> conditions = daoRegistry.getResourceDao(Condition.class)
                .search(conditionParams, new SystemRequestDetails()).getAllResources();

        if (allergies.isEmpty() && conditions.isEmpty()) {
            log.info("No local FHIR resources found for patient {}, returning empty IPS bundle", patientId.getIdPart());
            return buildEmptyBundle(patient);
        }

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

        final Bundle bundle = new Bundle();
        bundle.getMeta().addProfile("http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips");
        bundle.setType(Bundle.BundleType.DOCUMENT);
        bundle.setTimestamp(new Date());
        bundle.setIdentifier(new Identifier().setSystem("urn:oid:2.16.840.1.113883.3.72").setValue(UUID.randomUUID().toString()));
        bundle.addEntry().setFullUrl("urn:uuid:" + composition.getId()).setResource(composition);
        bundle.addEntry().setFullUrl(patientFullUrl).setResource(patient);

        // conditions section
        final Composition.SectionComponent conditionSection;
        if (conditions.isEmpty()) {
            conditionSection = emptySection("Active Problems", "http://loinc.org", "11450-4", "Problem list Reported");
        } else {
            conditionSection = new Composition.SectionComponent();
            conditionSection.setTitle("Active Problems");
            conditionSection.setCode(new CodeableConcept(new Coding("http://loinc.org", "11450-4", "Problem list Reported")));
            final Narrative text = new Narrative();
            text.setStatusAsString("generated");
            text.setDivAsString("<div xmlns=***REMOVED***"http://www.w3.org/1999/xhtml***REMOVED***">Active Problems</div>");
            conditionSection.setText(text);
            for (final IBaseResource r : conditions) {
                final Condition condition = (Condition) r;
                final String entryUuid = UUID.randomUUID().toString();
                condition.setId(entryUuid);
                condition.setSubject(new Reference(patientFullUrl));
                conditionSection.addEntry(new Reference("urn:uuid:" + entryUuid));
                bundle.addEntry().setFullUrl("urn:uuid:" + entryUuid).setResource(condition);
            }
        }
        composition.addSection(conditionSection);

        // allergies section
        final Composition.SectionComponent allergySection;
        if (allergies.isEmpty()) {
            allergySection = emptySection("Active Allergies and Intolerances", "http://loinc.org", "48765-2", "Allergies and Intolerances");
        } else {
            allergySection = new Composition.SectionComponent();
            allergySection.setTitle("Active Allergies and Intolerances");
            allergySection.setCode(new CodeableConcept(new Coding("http://loinc.org", "48765-2", "Allergies and Intolerances")));
            final Narrative text = new Narrative();
            text.setStatusAsString("generated");
            text.setDivAsString("<div xmlns=***REMOVED***"http://www.w3.org/1999/xhtml***REMOVED***">Active Allergies and Intolerances</div>");
            allergySection.setText(text);
            for (final IBaseResource r : allergies) {
                final AllergyIntolerance allergy = (AllergyIntolerance) r;
                final String entryUuid = UUID.randomUUID().toString();
                allergy.setId(entryUuid);
                allergy.setPatient(new Reference(patientFullUrl));
                allergySection.addEntry(new Reference("urn:uuid:" + entryUuid));
                bundle.addEntry().setFullUrl("urn:uuid:" + entryUuid).setResource(allergy);
            }
        }
        composition.addSection(allergySection);

        // medications section — always empty for fhir flow (not stored locally)
        composition.addSection(emptySection("Active Medication List", "http://loinc.org", "10160-0", "Medication List"));

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
        text.setDivAsString("<div xmlns=***REMOVED***"http://www.w3.org/1999/xhtml***REMOVED***">" + title + "</div>");
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
