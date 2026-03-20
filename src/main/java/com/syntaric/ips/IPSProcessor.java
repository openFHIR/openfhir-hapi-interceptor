package com.syntaric.ips;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.ips.api.IpsContext;
import ca.uhn.fhir.jpa.ips.api.IpsSectionContext;
import ca.uhn.fhir.jpa.ips.api.ISectionResourceSupplier;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syntaric.PixManager;
import com.syntaric.hapi.IpsBundleInterceptor;
import com.syntaric.openfhir.aql.ToAqlResponse;
import com.syntaric.openehr.OpenEhrCdrClient;
import com.syntaric.openehr.OpenEhrCdrRegistry;
import com.syntaric.openfhir.OpenFhirClient;
import com.syntaric.openfhir.aql.ToAqlRequest;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;

import java.util.*;

import org.springframework.stereotype.Component;

@Component
@Slf4j
public class IPSProcessor implements ISectionResourceSupplier {

    private static final Map<Class<? extends IBaseResource>, String> SECTION_FHIR_PATHS = Map.of(
            AllergyIntolerance.class, "/AllergyIntolerance",
            MedicationStatement.class, "/MedicationStatement",
            Condition.class, "/Condition?verification-status=confirmed"
    );

    private final OpenFhirClient openFhirClient;
    private final OpenEhrCdrRegistry openEhrCdrRegistry;
    private final FhirContext fhirContext;
    private final PixManager pixManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IPSProcessor(final OpenFhirClient openFhirClient, final OpenEhrCdrRegistry openEhrCdrRegistry,
                        final PixManager pixManager) {
        this.openFhirClient = openFhirClient;
        this.openEhrCdrRegistry = openEhrCdrRegistry;
        this.fhirContext = FhirContext.forR4();
        this.pixManager = pixManager;
    }

    @Nullable
    @Override
    public <T extends IBaseResource> List<ResourceEntry> fetchResourcesForSection(
            final IpsContext theIpsContext,
            final IpsSectionContext<T> theSectionContext,
            final RequestDetails theRequestDetails) {

        String fhirPath = SECTION_FHIR_PATHS.get(theSectionContext.getResourceType());
        if (fhirPath == null) {
            log.info("No IPS query defined for resource type {}, skipping", theSectionContext.getResourceType().getSimpleName());
            return null;
        }

        final String cdrName = theRequestDetails.getHeader(OpenEhrCdrRegistry.TARGET_CDR_HEADER);
        final String resolvedCdrName = openEhrCdrRegistry.resolveName(cdrName);

        final String idPart = theIpsContext.getSubjectId().getIdPart(); // ???
        Optional<String> ehrId = pixManager.resolveById(idPart, IpsBundleInterceptor.EHRID_SYSTEM, resolvedCdrName);
        if (!ehrId.isPresent()) {
            throw new IllegalArgumentException(String.format("No ehrId exists on this patient %s for system %s",
                    idPart, cdrName));
        }

        log.info("Fetching IPS section {} for ehrId {}", fhirPath, ehrId);

        final ToAqlResponse toAqlResponse = openFhirClient.getAql(new ToAqlRequest("International Patient Summary", ehrId.get(), fhirPath));
        if (toAqlResponse.getAqls() == null || toAqlResponse.getAqls().isEmpty()) {
            return null;
        }

        final OpenEhrCdrClient cdrClient = openEhrCdrRegistry.resolve(cdrName);

        // fire all AQLs, flatmap rows from each result into a single archetype array
        final List<JsonNode> allRows = new ArrayList<>();
        for (final ToAqlResponse.AqlResponse aqlResponse : toAqlResponse.getAqls()) {
            if (aqlResponse.getType() == ToAqlResponse.AqlType.COMPOSITION) {
                continue;
            }
            final String openEhrResult = cdrClient.queryAql(aqlResponse.getAql());
            allRows.addAll(extractArchetypeRows(openEhrResult));
        }

        if (allRows.isEmpty()) {
            return null;
        }

        // single toFhir call with all rows combined
        final String fhirJson = openFhirClient.toFhir(allRows);
        final Bundle bundle = fhirContext.newJsonParser().parseResource(Bundle.class, fhirJson);
        return extractSectionResources(bundle);
    }

    private List<ResourceEntry> extractSectionResources(final Bundle bundle) {
        final List<ResourceEntry> entries = new ArrayList<>();
        bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(r -> r instanceof Composition)
                .map(r -> (Composition) r)
                .flatMap(c -> c.getSection().stream())
                .flatMap(s -> s.getEntry().stream())
                .map(BaseReference::getResource)
                .filter(Objects::nonNull)
                .forEach(resource -> {
                    resource.setId(UUID.randomUUID().toString());
                    entries.add(new ResourceEntry(resource, InclusionTypeEnum.PRIMARY_RESOURCE));
                });
        return entries;
    }

    public List<JsonNode> extractArchetypeRows(final String aqlResultJson) {
        try {
            final JsonNode rows = objectMapper.readTree(aqlResultJson).path("rows");
            final List<JsonNode> result = new ArrayList<>();
            for (final JsonNode row : rows) {
                if (row.isArray() && !row.isEmpty()) {
                    result.add(row.get(0));
                }
            }
            return result;
        } catch (final Exception e) {
            throw new RuntimeException("Failed to parse AQL result rows: " + e.getMessage(), e);
        }
    }
}
