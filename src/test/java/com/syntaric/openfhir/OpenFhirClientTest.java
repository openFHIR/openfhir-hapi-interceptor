package com.syntaric.openfhir;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.syntaric.openfhir.aql.ToAqlRequest;
import com.syntaric.openfhir.aql.ToAqlResponse;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Provenance;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenFhirClientTest {

    private record StubResponse(int status, String body) {}

    private record RecordedRequest(String path, String query, String contentType, String reqId, String body) {}

    private static final FhirContext fhirContext = FhirContext.forR4Cached();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Deque<StubResponse> stubs = new ConcurrentLinkedDeque<>();
    private static final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());

    private static HttpServer server;
    private static OpenFhirClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new RecordedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders().getFirst("Content-type"),
                    exchange.getRequestHeaders().getFirst("x-req-id"),
                    body));
            final StubResponse stub = stubs.poll();
            final int status = stub != null ? stub.status() : 500;
            final byte[] responseBytes =
                    (stub != null ? stub.body() : "").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, responseBytes.length == 0 ? -1 : responseBytes.length);
            if (responseBytes.length > 0) {
                exchange.getResponseBody().write(responseBytes);
            }
            exchange.close();
        });
        server.start();

        final OpenFhirProperties properties = new OpenFhirProperties();
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        client = new OpenFhirClient(properties, fhirContext);
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        stubs.clear();
        requests.clear();
    }

    // ---------------------------------------------------------------- convert

    @Test
    void convertPostsBundleToToOpenEhrAndUnwrapsComposition() {
        stubs.add(new StubResponse(200, encode(parametersWithComposition("{\"_type\": \"COMPOSITION\"}"))));

        final Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTION);
        final String result = client.convert(bundle, "req-1");

        assertEquals("{\"_type\": \"COMPOSITION\"}", result);
        final RecordedRequest req = requests.get(0);
        assertEquals("/$toopenehr", req.path());
        assertEquals("format=canonical", req.query());
        assertEquals("application/fhir+json", req.contentType());
        assertEquals("req-1", req.reqId());
        final Bundle sent = fhirContext.newJsonParser().parseResource(Bundle.class, req.body());
        assertEquals(Bundle.BundleType.TRANSACTION, sent.getType());
    }

    @Test
    void convertToleratesOutcomeParameter() {
        final Parameters response = parametersWithComposition("COMPO");
        final OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.WARNING)
                .setCode(OperationOutcome.IssueType.PROCESSING)
                .setDiagnostics("partial mapping");
        response.addParameter().setName("outcome").setResource(outcome);
        stubs.add(new StubResponse(200, encode(response)));

        assertEquals("COMPO", client.convert(new Bundle(), "req-2"));
    }

    @Test
    void convertThrowsWhenCompositionParameterMissing() {
        final String responseBody = encode(new Parameters());
        stubs.add(new StubResponse(200, responseBody));

        final OpenFhirException ex =
                assertThrows(OpenFhirException.class, () -> client.convert(new Bundle(), "req-3"));
        assertTrue(ex.getMessage().contains("missing composition"));
        assertEquals(responseBody, ex.getResponseBody());
    }

    @Test
    void convertErrorIncludesOperationOutcomeDiagnostics() {
        final OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
                .setCode(OperationOutcome.IssueType.INVALID)
                .setDiagnostics("bad payload");
        stubs.add(new StubResponse(400, encode(outcome)));

        final OpenFhirException ex =
                assertThrows(OpenFhirException.class, () -> client.convert(new Bundle(), "req-4"));
        assertTrue(ex.getMessage().contains("400"));
        assertTrue(ex.getMessage().contains("bad payload"));
        assertEquals(400, ex.getStatusCode());
    }

    // ----------------------------------------------------------------- toFhir

    @Test
    void toFhirSendsParametersWithCompositionTemplateIdAndContext() throws Exception {
        stubs.add(new StubResponse(200, encode(bundleOf(new Patient()))));

        final List<JsonNode> rows = List.of(objectMapper.readTree("{\"a\":1}"));
        client.toFhir(rows, "req-5", "IPS template", "ehr-123", "Patient/42");

        final RecordedRequest req = requests.get(0);
        assertEquals("/$tofhir", req.path());
        assertNull(req.query());
        assertEquals("application/fhir+json", req.contentType());
        final Parameters sent = fhirContext.newJsonParser().parseResource(Parameters.class, req.body());
        assertEquals("[{\"a\":1}]", ((StringType) param(sent, "composition").getValue()).getValue());
        assertEquals("IPS template", ((StringType) param(sent, "templateId").getValue()).getValue());
        final Parameters.ParametersParameterComponent context = param(sent, "context");
        assertNotNull(context);
        assertEquals("ehr-123", ((StringType) part(context, "ehr_id").getValue()).getValue());
        assertEquals("Patient/42", ((Reference) part(context, "patient").getValue()).getReference());
    }

    @Test
    void toFhirOmitsBlankTemplateId() {
        stubs.add(new StubResponse(200, encode(bundleOf(new Patient()))));

        client.toFhir("[]", "req-6", " ", "ehr-123", "Patient/42");

        final Parameters sent =
                fhirContext.newJsonParser().parseResource(Parameters.class, requests.get(0).body());
        assertNull(param(sent, "templateId"));
        assertNotNull(param(sent, "composition"));
    }

    @Test
    void toFhirOmitsContextWhenEhrIdAndPatientBlank() {
        stubs.add(new StubResponse(200, encode(bundleOf(new Patient()))));

        client.toFhir("[]", "req-6b", "IPS template", null, " ");

        final Parameters sent =
                fhirContext.newJsonParser().parseResource(Parameters.class, requests.get(0).body());
        assertNull(param(sent, "context"));
    }

    @Test
    void toFhirStripsEngineProvenanceAndOperationOutcome() {
        final Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(3);
        final Patient patient = new Patient();
        patient.setId("p1");
        bundle.addEntry().setResource(patient);
        bundle.addEntry().setResource(engineProvenance());
        final OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
                .setCode(OperationOutcome.IssueType.PROCESSING)
                .setDiagnostics("mapping issue");
        bundle.addEntry().setResource(outcome);
        stubs.add(new StubResponse(200, encode(bundle)));

        final Bundle result = parseBundle(client.toFhir("[]", "req-7", null, null, null));

        assertEquals(1, result.getEntry().size());
        assertEquals("Patient", result.getEntryFirstRep().getResource().fhirType());
        assertEquals(1, result.getTotal());
    }

    @Test
    void toFhirKeepsUnmarkedProvenanceWhenMarkedOnePresent() {
        final Bundle bundle = new Bundle();
        final Patient patient = new Patient();
        patient.setId("p1");
        bundle.addEntry().setResource(patient);
        final Provenance mappingProvenance = new Provenance();
        mappingProvenance.setId("mapping");
        bundle.addEntry().setResource(mappingProvenance);
        bundle.addEntry().setResource(engineProvenance());
        stubs.add(new StubResponse(200, encode(bundle)));

        final Bundle result = parseBundle(client.toFhir("[]", "req-8", null, null, null));

        assertEquals(2, result.getEntry().size());
        assertEquals("mapping", result.getEntry().get(1).getResource().getIdElement().getIdPart());
    }

    @Test
    void toFhirFallbackStripsTrailingUnmarkedProvenance() {
        final Bundle bundle = new Bundle();
        final Patient patient = new Patient();
        patient.setId("p1");
        bundle.addEntry().setResource(patient);
        final Provenance unmarked = new Provenance();
        unmarked.setId("trailing");
        bundle.addEntry().setResource(unmarked);
        stubs.add(new StubResponse(200, encode(bundle)));

        final Bundle result = parseBundle(client.toFhir("[]", "req-9", null, null, null));

        assertEquals(1, result.getEntry().size());
        assertEquals("Patient", result.getEntryFirstRep().getResource().fhirType());
    }

    @Test
    void toFhirErrorIncludesOperationOutcomeDiagnostics() {
        final OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
                .setCode(OperationOutcome.IssueType.EXCEPTION)
                .setDiagnostics("engine exploded");
        stubs.add(new StubResponse(500, encode(outcome)));

        final OpenFhirException ex = assertThrows(OpenFhirException.class,
                () -> client.toFhir("[]", "req-10", null, null, null));
        assertTrue(ex.getMessage().contains("500"));
        assertTrue(ex.getMessage().contains("engine exploded"));
    }

    @Test
    void errorDetailFallsBackToIssueDetailsTextWhenDiagnosticsAbsent() {
        final OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
                .setCode(OperationOutcome.IssueType.REQUIRED)
                .setDetails(new CodeableConcept().setText("templateId is required for flat compositions"));
        stubs.add(new StubResponse(400, encode(outcome)));

        final OpenFhirException ex = assertThrows(OpenFhirException.class,
                () -> client.toFhir("[]", "req-12", null, null, null));
        assertTrue(ex.getMessage().contains("templateId is required for flat compositions"),
                   "expected details.text in message but was: " + ex.getMessage());
    }

    // ----------------------------------------------------------------- getAql

    @Test
    void getAqlStillUsesLegacyToAqlEndpoint() {
        stubs.add(new StubResponse(200,
                "{\"aqls\":[{\"aql\":\"SELECT c FROM COMPOSITION c\",\"type\":\"ENTRY\",\"templateId\":\"T\"}]}"));

        final ToAqlResponse response =
                client.getAql(new ToAqlRequest("T", "ehr-123", "/Condition"), "req-11");

        final RecordedRequest req = requests.get(0);
        assertEquals("/openfhir/toaql", req.path());
        assertEquals("application/json", req.contentType());
        assertEquals(1, response.getAqls().size());
        assertEquals("SELECT c FROM COMPOSITION c", response.getAqls().get(0).getAql());
    }

    // ---------------------------------------------------------------- helpers

    private static String encode(final IBaseResource resource) {
        return fhirContext.newJsonParser().encodeResourceToString(resource);
    }

    private static Bundle parseBundle(final String json) {
        return fhirContext.newJsonParser().parseResource(Bundle.class, json);
    }

    private static Parameters parametersWithComposition(final String composition) {
        final Parameters parameters = new Parameters();
        parameters.addParameter().setName("composition").setValue(new StringType(composition));
        return parameters;
    }

    private static Bundle bundleOf(final Patient patient) {
        final Bundle bundle = new Bundle();
        bundle.addEntry().setResource(patient);
        return bundle;
    }

    private static Provenance engineProvenance() {
        final Provenance provenance = new Provenance();
        provenance.setId("engine");
        provenance.addEntity()
                .setRole(Provenance.ProvenanceEntityRole.SOURCE)
                .setWhat(new Reference().setIdentifier(
                        new Identifier().setSystem("urn:openfhir:templateId").setValue("IPS template")));
        return provenance;
    }

    private static Parameters.ParametersParameterComponent param(final Parameters parameters, final String name) {
        return parameters.getParameter().stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst()
                .orElse(null);
    }

    private static Parameters.ParametersParameterComponent part(
            final Parameters.ParametersParameterComponent parameter, final String name) {
        return parameter.getPart().stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst()
                .orElse(null);
    }
}
