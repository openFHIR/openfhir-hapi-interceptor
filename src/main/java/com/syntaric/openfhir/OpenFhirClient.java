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
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static java.nio.charset.Charset.defaultCharset;

@Component
@Slf4j
public class OpenFhirClient {

    private static final String TO_OPENFHIR_PATH = "/openfhir/toopenehr";
    private static final String TO_AQL_PATH = "/openfhir/toaql";
    private static final String TO_FHIR_PATH = "/openfhir/tofhir";

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
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(fhirPayload))
                .build();

        try {
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OpenFhirException("OpenFHIR toopenehr failed with status " + response.statusCode(),
                        response.statusCode(), response.body(), response.headers());
            }
            log.info("OpenFHIR toopenehr successful, status={}", response.statusCode());
            return response.body();
        } catch (final OpenFhirException e) {
            throw e;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenFhirException("OpenFHIR toopenehr request interrupted", e);
        } catch (final Exception e) {
            throw new OpenFhirException("OpenFHIR toopenehr request failed", e);
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

    public String toFhir(final String openEhrQueryResult, final String reqId, final String templateId) {
        try {
            final String templateIdQueryParam = StringUtils.isEmpty(templateId) ? "" : String.format("?templateId=%s", URLEncoder.encode(templateId, defaultCharset()));
            final HttpRequest request = newRequestBuilder(TO_FHIR_PATH + templateIdQueryParam, reqId)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(openEhrQueryResult))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OpenFhirException("OpenFHIR tofhir failed with status " + response.statusCode(),
                        response.statusCode(), response.body(), response.headers());
            }
            log.info("OpenFHIR tofhir successful, status={}, reqId={}", response.statusCode(), reqId);
            return response.body();
        } catch (final OpenFhirException e) {
            throw e;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenFhirException("OpenFHIR tofhir request interrupted", e);
        } catch (final Exception e) {
            throw new OpenFhirException("OpenFHIR tofhir request failed", e);
        }
    }

    public String toFhir(final List<JsonNode> archetypeRows, final String reqId, final String templateId) {
        try {
            final String payload = objectMapper.writeValueAsString(archetypeRows);
            return toFhir(payload, reqId, templateId);
        } catch (final OpenFhirException e) {
            throw e;
        } catch (final Exception e) {
            throw new OpenFhirException("Failed to serialize archetype rows for tofhir", e);
        }
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
