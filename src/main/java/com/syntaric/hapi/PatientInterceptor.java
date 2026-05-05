package com.syntaric.hapi;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import com.syntaric.PixManager;
import com.syntaric.openehr.OpenEhrCdrClient;
import com.syntaric.openehr.OpenEhrCdrRegistry;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

/**
 * Intercepts successful {@link Patient} creation, provisions a new EHR record in the
 * OpenEHR CDR, and stores the resulting EHR ID back onto the patient as an identifier
 * with system {@value Constants#EHRID_SYSTEM}.
 *
 * <p>The hook fires on {@link Pointcut#STORAGE_PRECOMMIT_RESOURCE_CREATED}, which runs
 * after HAPI has persisted the resource but <em>before</em> the transaction commits.
 * If EHR provisioning fails, an {@link InternalErrorException} is thrown to abort the
 * transaction, rolling back the patient creation automatically.
 */
@Component
@Interceptor
@Slf4j
public class PatientInterceptor {

    private final OpenEhrCdrRegistry openEhrCdrRegistry;
    private final PixManager pixManager;

    public PatientInterceptor(final OpenEhrCdrRegistry openEhrCdrRegistry,
                              final PixManager pixManager) {
        this.openEhrCdrRegistry = openEhrCdrRegistry;
        this.pixManager = pixManager;
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void onPatientCreated(final IBaseResource resource, final RequestDetails requestDetails) {
        if (!(resource instanceof Patient patient)) {
            return;
        }

        final String patientId = patient.getIdElement().getIdPart();
        log.info("Patient {} created, provisioning EHR record", patientId);

        final String cdrHeader = requestDetails.getHeader(OpenEhrCdrRegistry.TARGET_CDR_HEADER);

        final String[] cdrNames = (cdrHeader != null && !cdrHeader.isBlank())
                ? cdrHeader.split(",")
                : new String[]{null};

        for (final String raw : cdrNames) {
            final String cdrName = raw != null ? raw.trim() : null;
            final String resolvedCdrName = openEhrCdrRegistry.resolveName(cdrName);
            final OpenEhrCdrClient cdrClient = openEhrCdrRegistry.resolve(cdrName);
            try {
                patient.addIdentifier(pixManager.provisionEhrForPatient(patientId, cdrClient, resolvedCdrName));
            } catch (final Exception e) {
                log.error("EHR provisioning failed for patient {} on CDR '{}' — aborting patient creation", patientId, resolvedCdrName, e);
                throw new InternalErrorException("Failed to provision EHR record in OpenEHR CDR '" + resolvedCdrName + "' for patient " + patientId, e);
            }
        }
    }
}
