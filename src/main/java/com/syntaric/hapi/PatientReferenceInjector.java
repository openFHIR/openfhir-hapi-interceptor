package com.syntaric.hapi;

import ca.uhn.fhir.util.FhirTerser;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.List;
import java.util.Map;

@Slf4j
public final class PatientReferenceInjector {

    // Per-resource-type FHIRPath expressions that may hold a patient reference,
    // tried in order until one resolves to a non-empty Patient reference.
    public static final Map<String, List<String>> PATIENT_PATHS = Map.ofEntries(
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

    private PatientReferenceInjector() {}

    /**
     * Sets the patient reference on {@code resource} to {@code patientReference}.
     * Uses only the first (primary) path for the resource type.
     * If the field is already present it is overwritten; if absent it is created.
     */
    public static void injectPatientReference(final FhirTerser terser,
                                              final IBaseResource resource,
                                              final String patientReference) {
        final List<String> paths = PATIENT_PATHS.get(resource.fhirType());
        if (paths == null) {
            return;
        }
        // Use only the primary (first) patient path
        final String path = paths.get(0);
        try {
            final List<IBaseReference> refs = terser.getValues(resource, path, IBaseReference.class);
            if (!refs.isEmpty()) {
                refs.get(0).setReference(patientReference);
            } else {
                // Field absent — create it
                final IBaseReference newRef = (IBaseReference) terser.addElement(resource, path);
                newRef.setReference(patientReference);
            }
        } catch (final Exception e) {
            log.debug("Could not inject patient reference at path '{}' on {}: {}", path, resource.fhirType(), e.getMessage());
        }
    }
}
