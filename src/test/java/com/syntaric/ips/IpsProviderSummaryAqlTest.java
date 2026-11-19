package com.syntaric.ips;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syntaric.InterceptorProperties;
import com.syntaric.openehr.OpenEhrAqlUtil;
import com.syntaric.openehr.OpenEhrCdrClient;
import com.syntaric.openfhir.OpenFhirClient;
import com.syntaric.openfhir.OpenFhirException;
import com.syntaric.openfhir.aql.ToAqlRequest;
import com.syntaric.openfhir.aql.ToAqlResponse;
import com.syntaric.openfhir.OpenFhirProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpsProviderSummaryAqlTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TEMPLATE = "EPS Patient Summary";
    private static final String EHR_ID = "c4c8096d-72ef-4447-bf40-5a5cc942a72d";
    private static final String CONFIGURED_AQL = "SELECT c FROM EHR e CONTAINS COMPOSITION c"
            + "[openEHR-EHR-COMPOSITION.health_summary.v1] WHERE e/ehr_id/value = '" + EHR_ID + "'";

    // --- which /toaql answer is a configured _query ---

    @Test
    void summaryFhirPathEndsInTheOperation() {
        // openFHIR matches a _query rule "$summary" only on a path whose last segment is the operation
        assertEquals("/Patient/123/$summary", IpsProvider.summaryFhirPath("123"));
    }

    @Test
    void configuredQueryIsTheOnlyCompositionAql() throws Exception {
        // what openFHIR's /toaql answers when a context's _query matches: one COMPOSITION AQL with its template
        final ToAqlResponse response = objectMapper.readValue("""
                {"aqls":[{"aql":"%s","type":"COMPOSITION","templateId":"%s"}]}
                """.formatted(CONFIGURED_AQL, TEMPLATE), ToAqlResponse.class);

        assertEquals(CONFIGURED_AQL, IpsProvider.configuredSummaryAql(response, TEMPLATE, EHR_ID));
    }

    @Test
    void templateIdsCompareAsOpenFhirDoes() {
        assertEquals(CONFIGURED_AQL,
                     IpsProvider.configuredSummaryAql(configured(CONFIGURED_AQL, "eps_patient_summary"), TEMPLATE,
                                                      EHR_ID));
    }

    @Test
    void anotherTemplatesQueryIsNotUsed() {
        // openFHIR also answers with the _query of a context other than the one the request names
        assertNull(IpsProvider.configuredSummaryAql(configured(CONFIGURED_AQL, "Some Other Summary"), TEMPLATE,
                                                    EHR_ID));
        assertNull(IpsProvider.configuredSummaryAql(configured(CONFIGURED_AQL, null), TEMPLATE, EHR_ID));
    }

    @Test
    void aQueryNotScopedToThePatientIsNotUsed() {
        // a _query without {{ehrid}} comes back without the EHR id, and would read every patient's compositions
        final String unscoped = "SELECT c FROM EHR e CONTAINS COMPOSITION c[openEHR-EHR-COMPOSITION.health_summary.v1]";

        assertNull(IpsProvider.configuredSummaryAql(configured(unscoped, TEMPLATE), TEMPLATE, EHR_ID));
    }

    @Test
    void generatedAqlsAreNotAConfiguredQuery() {
        // AQLs openFHIR generates from the mappings: an ENTRY AQL together with its COMPOSITION AQL
        final ToAqlResponse response = new ToAqlResponse()
                .addAql("SELECT h FROM EHR e CONTAINS EVALUATION h", ToAqlResponse.AqlType.ENTRY)
                .addAql(CONFIGURED_AQL, ToAqlResponse.AqlType.COMPOSITION);

        assertNull(IpsProvider.configuredSummaryAql(response, TEMPLATE, EHR_ID));
    }

    @Test
    void aLoneEntryAqlIsNotAConfiguredQuery() {
        final ToAqlResponse response = new ToAqlResponse()
                .addAql("SELECT h FROM EHR e[ehr_id/value='" + EHR_ID + "'] CONTAINS EVALUATION h",
                        ToAqlResponse.AqlType.ENTRY);

        assertNull(IpsProvider.configuredSummaryAql(response, TEMPLATE, EHR_ID));
    }

    @Test
    void noAqlsIsNotAConfiguredQuery() throws Exception {
        assertNull(IpsProvider.configuredSummaryAql(new ToAqlResponse(), TEMPLATE, EHR_ID));
        assertNull(IpsProvider.configuredSummaryAql(objectMapper.readValue("{\"aqls\":[]}", ToAqlResponse.class),
                                                    TEMPLATE, EHR_ID));
        assertNull(IpsProvider.configuredSummaryAql(null, TEMPLATE, EHR_ID));
    }

    // --- the flow: configured AQL on the CDR, or the fallback to the per-section queries ---

    @Test
    void configuredQueryRunsOnceAndItsRowsAreTheSummary() throws Exception {
        final StubOpenFhir openFhir = StubOpenFhir.answering(configured(CONFIGURED_AQL, TEMPLATE));
        final StubCdr cdr = new StubCdr("""
                {"rows":[[{"_type":"COMPOSITION","name":{"value":"EPS"}}]]}
                """);

        final Optional<List<JsonNode>> rows = provider(openFhir).configuredSummaryRows("p1", EHR_ID, cdr, "req-1");

        assertEquals(List.of("/Patient/p1/$summary"), openFhir.paths);
        assertEquals(List.of(CONFIGURED_AQL), cdr.queries);
        assertEquals(1, rows.orElseThrow().size());
        assertEquals("COMPOSITION", rows.get().get(0).path("_type").asText());
    }

    @Test
    void noConfiguredQueryFallsBackWithoutTouchingTheCdr() {
        final StubOpenFhir openFhir = StubOpenFhir.answering(new ToAqlResponse());
        final StubCdr cdr = new StubCdr("{\"rows\":[]}");

        assertTrue(provider(openFhir).configuredSummaryRows("p1", EHR_ID, cdr, "req-2").isEmpty());
        assertTrue(cdr.queries.isEmpty());
    }

    @Test
    void anOpenFhirFailureFallsBack() {
        final StubOpenFhir openFhir = StubOpenFhir.failing(new OpenFhirException("boom", 500, "{}", null));
        final StubCdr cdr = new StubCdr("{\"rows\":[]}");

        assertTrue(provider(openFhir).configuredSummaryRows("p1", EHR_ID, cdr, "req-3").isEmpty());
        assertTrue(cdr.queries.isEmpty());
    }

    @Test
    void aConfiguredQueryThatFindsNothingFallsBack() {
        // e.g. a patient whose EHR has no composition of the summary template: the per-section queries still
        // find entries of other templates, as before
        final StubOpenFhir openFhir = StubOpenFhir.answering(configured(CONFIGURED_AQL, TEMPLATE));
        final StubCdr cdr = new StubCdr("{\"rows\":[]}");

        assertTrue(provider(openFhir).configuredSummaryRows("p1", EHR_ID, cdr, "req-4").isEmpty());
        assertEquals(List.of(CONFIGURED_AQL), cdr.queries);
    }

    private static ToAqlResponse configured(final String aql, final String templateId) {
        final ToAqlResponse.AqlResponse response = new ToAqlResponse.AqlResponse(aql,
                                                                                 ToAqlResponse.AqlType.COMPOSITION);
        response.setTemplateId(templateId);
        return new ToAqlResponse().addAql(response);
    }

    private static IpsProvider provider(final OpenFhirClient openFhirClient) {
        final InterceptorProperties properties = new InterceptorProperties();
        properties.getIps().setTemplateId(TEMPLATE);
        return new IpsProvider(null, null, null, openFhirClient, new OpenEhrAqlUtil(), FhirContext.forR4Cached(),
                               properties);
    }

    private static final class StubOpenFhir extends OpenFhirClient {
        private final ToAqlResponse answer;
        private final OpenFhirException failure;
        private final List<String> paths = new ArrayList<>();

        private StubOpenFhir(final ToAqlResponse answer, final OpenFhirException failure) {
            super(properties(), FhirContext.forR4Cached());
            this.answer = answer;
            this.failure = failure;
        }

        private static OpenFhirProperties properties() {
            final OpenFhirProperties properties = new OpenFhirProperties();
            properties.setBaseUrl("http://openfhir.invalid");
            return properties;
        }

        static StubOpenFhir answering(final ToAqlResponse answer) {
            return new StubOpenFhir(answer, null);
        }

        static StubOpenFhir failing(final OpenFhirException failure) {
            return new StubOpenFhir(null, failure);
        }

        @Override
        public ToAqlResponse getAql(final ToAqlRequest toAqlRequest, final String reqId) {
            paths.add(toAqlRequest.getFhirFullUrl());
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private static final class StubCdr extends OpenEhrCdrClient {
        private final String result;
        private final List<String> queries = new ArrayList<>();

        private StubCdr(final String result) {
            super("http://cdr.invalid", null, null);
            this.result = result;
        }

        @Override
        public String queryAql(final String aql) {
            queries.add(aql);
            return result;
        }
    }
}
