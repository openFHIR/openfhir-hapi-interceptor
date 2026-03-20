package com.syntaric.ips;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.syntaric.PixManager;
import com.syntaric.hapi.IpsBundleInterceptor;
import com.syntaric.openehr.OpenEhrCdrClient;
import com.syntaric.openehr.OpenEhrCdrRegistry;
import com.syntaric.openfhir.OpenFhirClient;
import com.syntaric.openfhir.aql.ToAqlRequest;
import com.syntaric.openfhir.aql.ToAqlResponse;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class IpsDirectProvider {

    private static final String TEMPLATE_ID = "International Patient Summary";
    private static final List<String> FHIR_PATHS = List.of("/AllergyIntolerance", "/Condition?verification-status=confirmed");

    private final DaoRegistry daoRegistry;
    private final PixManager pixManager;
    private final OpenEhrCdrRegistry openEhrCdrRegistry;
    private final OpenFhirClient openFhirClient;
    private final IPSProcessor ipsProcessor;

    public IpsDirectProvider(final DaoRegistry daoRegistry,
                             final PixManager pixManager,
                             final OpenEhrCdrRegistry openEhrCdrRegistry,
                             final OpenFhirClient openFhirClient,
                             final IPSProcessor ipsProcessor) {
        this.daoRegistry = daoRegistry;
        this.pixManager = pixManager;
        this.openEhrCdrRegistry = openEhrCdrRegistry;
        this.openFhirClient = openFhirClient;
        this.ipsProcessor = ipsProcessor;
    }

    @Operation(name = "$summary-direct", idempotent = true, type = Patient.class)
    public Bundle patientSummaryDirect(
            @IdParam final IdType patientId,
            final RequestDetails requestDetails) {

        final String patientIdPart = patientId.getIdPart();
        log.info("$summary-direct called for patient {}", patientIdPart);

        // (1) get patient
        final Patient patient = daoRegistry.<Patient>getResourceDao(Patient.class)
                .read(patientId, new SystemRequestDetails());

        // (2) resolve EHR ID
        final String cdrName = requestDetails.getHeader(OpenEhrCdrRegistry.TARGET_CDR_HEADER);
        final String resolvedCdrName = openEhrCdrRegistry.resolveName(cdrName);
        final String ehrId = pixManager.resolveById(patientIdPart, IpsBundleInterceptor.EHRID_SYSTEM, resolvedCdrName)
                .orElseThrow(() -> new IllegalStateException(
                        "No EHR ID found for patient " + patientIdPart + " on CDR '" + resolvedCdrName + "'"));

        // (2) get AQLs for each fhir path from OpenFHIR
        final OpenEhrCdrClient cdrClient = openEhrCdrRegistry.resolve(cdrName);
        final List<JsonNode> allRows = new ArrayList<>();
        for (final String fhirPath : FHIR_PATHS) {
            final ToAqlResponse toAqlResponse = openFhirClient.getAql(new ToAqlRequest(TEMPLATE_ID, ehrId, fhirPath));
            if (toAqlResponse.getAqls() == null || toAqlResponse.getAqls().isEmpty()) {
                log.debug("No AQLs returned for fhirPath={}, skipping", fhirPath);
                continue;
            }

            // (3) trigger AQLs and collect rows
            for (final ToAqlResponse.AqlResponse aqlResponse : toAqlResponse.getAqls()) {
                if (aqlResponse.getType() == ToAqlResponse.AqlType.COMPOSITION) {
                    continue;
                }
                final String openEhrResult = cdrClient.queryAql(aqlResponse.getAql());
                allRows.addAll(ipsProcessor.extractArchetypeRows(openEhrResult));
            }
        }

        // (4) send all rows to toFhir
        log.info("Sending {} archetype rows to toFhir for patient {}", allRows.size(), patientIdPart);
        final String fhirJson = openFhirClient.toFhir(allRows);
        return openFhirClient.getFhirContext().newJsonParser().parseResource(Bundle.class, fhirJson);
    }
}
