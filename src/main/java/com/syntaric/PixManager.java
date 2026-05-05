package com.syntaric;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.syntaric.openehr.Constants;
import com.syntaric.openehr.OpenEhrCdrClient;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a cross-system patient identifier (PIX-style lookup).
 *
 * <p>Given either a FHIR logical ID or a scoped identifier ({@code system|value}),
 * finds the {@link Patient} in the local HAPI store and returns the value of the
 * first identifier whose system matches the requested target system.
 */
@Component
@Slf4j
public class PixManager {

    private final DaoRegistry daoRegistry;

    public PixManager(final DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    private IFhirResourceDao<Patient> patientDao() {
        return daoRegistry.getResourceDao(Patient.class);
    }

    /**
     * Looks up a patient by FHIR logical ID and returns the identifier value for {@code targetSystem}
     * whose assigner display matches {@code cdrName}.
     *
     * @param patientId    FHIR logical ID (e.g. {@code "123"})
     * @param targetSystem identifier system to return
     * @param cdrName      CDR name to match against {@code Identifier.assigner.display}
     * @return identifier value, or empty if the patient has no matching identifier
     * @throws ResourceNotFoundException if no patient with that ID exists
     */
    public Optional<String> resolveById(final String patientId, final String targetSystem, final String cdrName) {
        log.info("PIX lookup by id={}, targetSystem={}, cdrName={}", patientId, targetSystem, cdrName);
        final Patient patient = patientDao().read(new IdType("Patient", patientId), new SystemRequestDetails());
        return extractIdentifier(patient, targetSystem, cdrName);
    }

    /**
     * Looks up a patient by a scoped identifier and returns the identifier value for {@code targetSystem}.
     *
     * @param system       system of the supplied identifier (may be {@code null} for system-agnostic search)
     * @param value        value of the supplied identifier
     * @param targetSystem identifier system to return
     * @return identifier value, or empty if no matching patient is found or has no identifier for that system
     */
    public Optional<String> resolveByIdentifier(final String system, final String value, final String targetSystem, final String cdrName) {
        log.info("PIX lookup by identifier system={}, value={}, targetSystem={}, cdrName={}", system, value, targetSystem, cdrName);

        final SearchParameterMap params = new SearchParameterMap()
                .add(Patient.SP_IDENTIFIER, new TokenParam(system, value));

        final IBundleProvider results = patientDao().search(params, new SystemRequestDetails());
        final List<IBaseResource> resources = results.getResources(0, 1);

        if (resources.isEmpty()) {
            log.info("PIX lookup found no patient for identifier {}|{}", system, value);
            return Optional.empty();
        }

        final Patient patient = (Patient) resources.get(0);
        return extractIdentifier(patient, targetSystem, cdrName);
    }

    /**
     * Creates a new EHR in the given CDR and stores the resulting EHR ID on the patient
     * as an identifier with system {@value Constants#EHRID_SYSTEM} and assigner
     * display set to {@code resolvedCdrName}.
     *
     * @param patientId       FHIR logical ID of the patient to update
     * @param cdrClient       CDR client to provision the EHR in
     * @param resolvedCdrName canonical CDR name to store as assigner display
     * @return the newly created EHR ID
     */
    public Identifier provisionEhrForPatient(final String patientId, final OpenEhrCdrClient cdrClient, final String resolvedCdrName) {
        final String ehrId = cdrClient.createEhr();
        log.info("EHR created for patient {}, ehrId={}, cdr={}", patientId, ehrId, resolvedCdrName);

        final Patient patient = patientDao().read(new IdType(ResourceType.Patient.name(), patientId), new SystemRequestDetails());
        final Identifier ehrIdIdentifier = new Identifier()
                .setSystem(Constants.EHRID_SYSTEM)
                .setValue(ehrId)
                .setAssigner(new Reference().setDisplay(resolvedCdrName));
        patient.addIdentifier(ehrIdIdentifier);
        patientDao().update(patient, new SystemRequestDetails());
        log.info("Patient {} updated with ehrId={} for cdr={}", patientId, ehrId, resolvedCdrName);
        return ehrIdIdentifier;
    }

    private Optional<String> extractIdentifier(final Patient patient, final String targetSystem, final String cdrName) {
        return patient.getIdentifier().stream()
                .filter(id -> targetSystem.equals(id.getSystem()))
                .filter(id -> cdrName.equals(id.getAssigner().getDisplay()))
                .map(org.hl7.fhir.r4.model.Identifier::getValue)
                .findFirst();
    }
}
