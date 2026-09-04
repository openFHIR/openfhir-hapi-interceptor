package com.syntaric.openfhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syntaric.auth.ClientCredentialsTokenProvider;
import com.syntaric.openfhir.aql.ToAqlRequest;
import com.syntaric.openfhir.aql.ToAqlResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Provenance;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class OpenFhirClient {

    // openFHIR >= 3.0.0 FHIR-operations endpoints (server root, no /openfhir prefix).
    // format=canonical is the engine default; sent explicitly so the shape we parse is pinned here.
    private static final String TO_OPENFHIR_PATH = "/$toopenehr?format=canonical";
    // toaql has no 3.0.0 operations-style replacement and stays on the legacy path
    private static final String TO_AQL_PATH = "/openfhir/toaql";
    private static final String TO_FHIR_PATH = "/$tofhir";

    // identifier system the engine puts on the Provenance entry it appends to every $tofhir Bundle
    private static final String ENGINE_PROVENANCE_IDENTIFIER_SYSTEM = "urn:openfhir:templateId";

    private final HttpClient httpClient;

    private final FhirContext fhirContext;

    private final ObjectMapper objectMapper;
    private final OpenFhirProperties properties;
    private final ClientCredentialsTokenProvider tokenProvider;

    public OpenFhirClient(final OpenFhirProperties properties,
                          final FhirContext fhirContext) {
        this.properties = properties;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.fhirContext = fhirContext;
        this.tokenProvider = properties.getOauth2().isConfigured()
                ? new ClientCredentialsTokenProvider(properties.getOauth2())
                : null;
    }

    public String convert(final IBaseResource resource, final String reqId) {
        final IParser parser = fhirContext.newJsonParser();
        final String fhirPayload = parser.encodeResourceToString(resource);

        final HttpRequest request = newRequestBuilder(TO_OPENFHIR_PATH, reqId)
                .header("Content-Type", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(fhirPayload))
                .build();

        try {
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw operationError("$toopenehr", response);
            }
            log.info("OpenFHIR $toopenehr successful, status={}", response.statusCode());

            final Parameters responseParameters =
                    fhirContext.newJsonParser().parseResource(Parameters.class, response.body());
            String composition = null;
            for (final Parameters.ParametersParameterComponent parameter : responseParameters.getParameter()) {
                if ("composition".equals(parameter.getName()) && parameter.getValue() instanceof StringType value) {
                    composition = value.getValue();
                } else if ("outcome".equals(parameter.getName())
                        && parameter.getResource() instanceof OperationOutcome outcome) {
                    logOperationOutcome("$toopenehr", outcome, reqId);
                }
            }
            if (StringUtils.isBlank(composition)) {
                throw new OpenFhirException("OpenFHIR $toopenehr response missing composition parameter",
                        response.statusCode(), response.body(), response.headers());
            }
            return composition;
        } catch (final OpenFhirException e) {
            throw e;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenFhirException("OpenFHIR $toopenehr request interrupted", e);
        } catch (final Exception e) {
            throw new OpenFhirException("OpenFHIR $toopenehr request failed", e);
        }
    }

    public ToAqlResponse getAql(final ToAqlRequest toAqlRequest, final String reqId) {
        try {
            final String payload = objectMapper.writeValueAsString(toAqlRequest);

            final HttpRequest request = newRequestBuilder(TO_AQL_PATH, reqId)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OpenFhirException("OpenFHIR toaql failed with status " + response.statusCode(),
                        response.statusCode(), response.body(), response.headers());
            }
            log.info("OpenFHIR toaql successful, status={}, reqId={}", response.statusCode(), reqId);
            return objectMapper.readValue(response.body(), ToAqlResponse.class);
        } catch (final OpenFhirException e) {
            throw e;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenFhirException("OpenFHIR toaql request interrupted", e);
        } catch (final Exception e) {
            throw new OpenFhirException("OpenFHIR toaql request failed", e);
        }
    }

    public String toFhir(final String openEhrQueryResult, final String reqId, final String templateId,
                         final String ehrId, final String patientReference) {
        try {
            final Parameters requestParameters = new Parameters();
            requestParameters.addParameter().setName("composition").setValue(new StringType(openEhrQueryResult));
            if (StringUtils.isNotBlank(templateId)) {
                requestParameters.addParameter().setName("templateId").setValue(new StringType(templateId));
            }
            if (StringUtils.isNotBlank(ehrId) || StringUtils.isNotBlank(patientReference)) {
                final Parameters.ParametersParameterComponent context =
                        requestParameters.addParameter().setName("context");
                if (StringUtils.isNotBlank(ehrId)) {
                    context.addPart().setName("ehr_id").setValue(new StringType(ehrId));
                }
                if (StringUtils.isNotBlank(patientReference)) {
                    context.addPart().setName("patient").setValue(new Reference(patientReference));
                }
            }
            final String payload = fhirContext.newJsonParser().encodeResourceToString(requestParameters);

            final HttpRequest request = newRequestBuilder(TO_FHIR_PATH, reqId)
                    .header("Content-Type", "application/fhir+json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw operationError("$tofhir", response);
            }
            log.info("OpenFHIR $tofhir successful, status={}, reqId={}", response.statusCode(), reqId);
            return stripEngineEntries(response.body(), reqId);
        } catch (final OpenFhirException e) {
            throw e;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenFhirException("OpenFHIR $tofhir request interrupted", e);
        } catch (final Exception e) {
            throw new OpenFhirException("OpenFHIR $tofhir request failed", e);
        }
    }

    public String toFhir(final List<JsonNode> archetypeRows, final String reqId, final String templateId,
                         final String ehrId, final String patientReference) {
        try {
            final String payload = objectMapper.writeValueAsString(archetypeRows);
            return toFhir(payload, reqId, templateId, ehrId, patientReference);
        } catch (final OpenFhirException e) {
            throw e;
        } catch (final Exception e) {
            throw new OpenFhirException("Failed to serialize archetype rows for $tofhir", e);
        }
    }

    /**
     * Removes the engine-generated Provenance and OperationOutcome entries that openFHIR 3.0.0 appends
     * to every {@code $tofhir} bundle, so callers keep seeing legacy-shaped bundles.
     */
    private String stripEngineEntries(final String bundleJson, final String reqId) {
        final IParser parser = fhirContext.newJsonParser();
        final Bundle bundle = parser.parseResource(Bundle.class, bundleJson);
        int removed = 0;

        for (final Iterator<Bundle.BundleEntryComponent> iter = bundle.getEntry().iterator(); iter.hasNext(); ) {
            final Bundle.BundleEntryComponent entry = iter.next();
            if (entry.getResource() instanceof OperationOutcome outcome) {
                logOperationOutcome("$tofhir", outcome, reqId);
                iter.remove();
                removed++;
            }
        }

        boolean markedProvenanceRemoved = false;
        for (final Iterator<Bundle.BundleEntryComponent> iter = bundle.getEntry().iterator(); iter.hasNext(); ) {
            final Bundle.BundleEntryComponent entry = iter.next();
            if (entry.getResource() instanceof Provenance provenance && isEngineProvenance(provenance)) {
                iter.remove();
                removed++;
                markedProvenanceRemoved = true;
            }
        }

        // fallback: the engine always appends its Provenance last; only strip an unmarked one in that
        // position, and never any other Provenance — a mapping can legitimately produce them
        if (!markedProvenanceRemoved && !bundle.getEntry().isEmpty()
                && bundle.getEntry().get(bundle.getEntry().size() - 1).getResource() instanceof Provenance) {
            log.warn("OpenFHIR $tofhir: stripping trailing Provenance without engine marker, reqId={}", reqId);
            bundle.getEntry().remove(bundle.getEntry().size() - 1);
            removed++;
        }

        if (removed == 0) {
            return bundleJson;
        }
        if (bundle.hasTotal()) {
            bundle.setTotal(Math.max(0, bundle.getTotal() - removed));
        }
        return parser.encodeResourceToString(bundle);
    }

    private boolean isEngineProvenance(final Provenance provenance) {
        return provenance.getEntity().stream()
                .anyMatch(entity -> entity.getRole() == Provenance.ProvenanceEntityRole.SOURCE
                        && entity.getWhat().hasIdentifier()
                        && ENGINE_PROVENANCE_IDENTIFIER_SYSTEM.equals(entity.getWhat().getIdentifier().getSystem()));
    }

    private void logOperationOutcome(final String operation, final OperationOutcome outcome, final String reqId) {
        outcome.getIssue().forEach(issue ->
                log.warn("OpenFHIR {} reported issue: severity={}, code={}, diagnostics={}, reqId={}",
                        operation, issue.getSeverity(), issue.getCode(), issueDetail(issue), reqId));
    }

    /**
     * Human-readable text of an issue: {@code diagnostics} when set, otherwise {@code details.text}
     * — FHIR allows either, and the engine does not always populate diagnostics.
     */
    private String issueDetail(final OperationOutcome.OperationOutcomeIssueComponent issue) {
        if (StringUtils.isNotBlank(issue.getDiagnostics())) {
            return issue.getDiagnostics();
        }
        if (issue.hasDetails() && StringUtils.isNotBlank(issue.getDetails().getText())) {
            return issue.getDetails().getText();
        }
        return null;
    }

    private OpenFhirException operationError(final String operation, final HttpResponse<String> response) {
        final StringBuilder message = new StringBuilder(
                "OpenFHIR " + operation + " failed with status " + response.statusCode());
        final String body = response.body();
        if (body != null && body.contains("OperationOutcome")) {
            try {
                final OperationOutcome outcome =
                        fhirContext.newJsonParser().parseResource(OperationOutcome.class, body);
                outcome.getIssue().forEach(issue -> {
                    final String detail = issueDetail(issue);
                    if (StringUtils.isNotBlank(detail)) {
                        message.append(" [").append(issue.getCode() != null ? issue.getCode().toCode() : "?")
                                .append("] ").append(detail);
                    }
                });
            } catch (final Exception e) {
                log.debug("Could not parse error body as OperationOutcome", e);
            }
        }
        return new OpenFhirException(message.toString(), response.statusCode(), body, response.headers());
    }

    private HttpRequest.Builder newRequestBuilder(final String path, final String reqId) {
        final String effectiveReqId = (reqId != null && !reqId.isBlank()) ? reqId : UUID.randomUUID().toString();
        final HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + path))
                .header("x-req-id", effectiveReqId);
        if (tokenProvider != null) {
            builder.header("Authorization", "Bearer " + tokenProvider.getToken());
        }
        return builder;
    }
}
