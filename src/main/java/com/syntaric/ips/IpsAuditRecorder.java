package com.syntaric.ips;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.UUID;

/**
 * Creates and persists a {@link DeviceRequest} for each auditable step in the $summary flow.
 *
 * <p>Each DeviceRequest carries:
 * <ul>
 *   <li>{@code identifier} – system={@code req-id}, value=x-req-id header value</li>
 *   <li>Two {@code parameter} entries with code {@code req} and {@code resp}, each with a
 *       {@code valueCodeableConcept.text} describing the request/response for that step.</li>
 * </ul>
 */
@Component
@Slf4j
public class IpsAuditRecorder {

    static final String REQ_ID_SYSTEM = "req-id";
    static final String PARAM_INDEX = "index";
    static final String PARAM_REQ = "req";
    static final String PARAM_RESP = "resp";
    static final String PARAM_OPENFHIR_INSIGHTS = "openfhir-insights";

    private final DaoRegistry daoRegistry;

    public IpsAuditRecorder(final DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    public void record(final String reqId, final int stepIndex, final String stepName, final String reqText, final String respText, final String openfhirReqId) {
        record(reqId, stepIndex, stepName, reqText, respText, openfhirReqId, DeviceRequest.RequestIntent.PLAN);
    }

    public void recordError(final String reqId, final int stepIndex, final String stepName,
                            final String reqText, final String errorDetail,
                            final String openfhirReqId, final DeviceRequest.RequestIntent intent) {
        try {
            final DeviceRequest dr = new DeviceRequest();
            dr.setId(UUID.randomUUID().toString());
            dr.setStatus(DeviceRequest.DeviceRequestStatus.ENTEREDINERROR);
            dr.setIntent(intent);
            dr.setAuthoredOn(new Date());

            dr.addIdentifier()
                    .setSystem(REQ_ID_SYSTEM)
                    .setValue(reqId);

            dr.setCode(new CodeableConcept(new Coding()
                    .setSystem("http://snomed.info/sct")
                    .setCode("706689003")
                    .setDisplay("Application programme software")));

            dr.getSubject().setDisplay(stepName);

            dr.addParameter()
                    .setCode(new CodeableConcept().setText(PARAM_INDEX))
                    .setValue(new Quantity().setValue(stepIndex));

            dr.addParameter()
                    .setCode(new CodeableConcept().setText(PARAM_REQ))
                    .setValue(new CodeableConcept().setText(reqText));

            dr.addParameter()
                    .setCode(new CodeableConcept().setText("error"))
                    .setValue(new CodeableConcept().setText(errorDetail));

            if (openfhirReqId != null) {
                dr.addParameter()
                        .setCode(new CodeableConcept().setText(PARAM_OPENFHIR_INSIGHTS))
                        .setValue(new CodeableConcept().setText(openfhirReqId));
            }

            daoRegistry.getResourceDao(DeviceRequest.class).create(dr, new SystemRequestDetails());
            log.debug("Error audit DeviceRequest created for step {}/'{}', reqId={}", stepIndex, stepName, reqId);
        } catch (final Exception e) {
            log.warn("Failed to persist error audit DeviceRequest for step '{}', reqId={}: {}", stepName, reqId, e.getMessage());
        }
    }

    public void record(final String reqId, final int stepIndex, final String stepName, final String reqText, final String respText, final String openfhirReqId, final DeviceRequest.RequestIntent intent) {
        try {
            final DeviceRequest dr = new DeviceRequest();
            dr.setId(UUID.randomUUID().toString());
            dr.setStatus(DeviceRequest.DeviceRequestStatus.COMPLETED);
            dr.setIntent(intent);
            dr.setAuthoredOn(new Date());

            dr.addIdentifier()
                    .setSystem(REQ_ID_SYSTEM)
                    .setValue(reqId);

            // device is required by FHIR — use a display-only reference
            dr.setCode(new CodeableConcept(new Coding()
                    .setSystem("http://snomed.info/sct")
                    .setCode("706689003")
                    .setDisplay("Application programme software")));

            dr.getSubject().setDisplay(stepName);

            dr.addParameter()
                    .setCode(new CodeableConcept().setText(PARAM_INDEX))
                    .setValue(new Quantity().setValue(stepIndex));

            dr.addParameter()
                    .setCode(new CodeableConcept().setText(PARAM_REQ))
                    .setValue(new CodeableConcept().setText(reqText));

            dr.addParameter()
                    .setCode(new CodeableConcept().setText(PARAM_RESP))
                    .setValue(new CodeableConcept().setText(respText));

            if (openfhirReqId != null) {
                dr.addParameter()
                        .setCode(new CodeableConcept().setText(PARAM_OPENFHIR_INSIGHTS))
                        .setValue(new CodeableConcept().setText(openfhirReqId));
            }

            daoRegistry.getResourceDao(DeviceRequest.class).create(dr, new SystemRequestDetails());
            log.debug("Audit DeviceRequest created for step {}/'{}', reqId={}", stepIndex, stepName, reqId);
        } catch (final Exception e) {
            log.warn("Failed to persist audit DeviceRequest for step '{}', reqId={}: {}", stepName, reqId, e.getMessage());
        }
    }
}
