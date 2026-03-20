package com.syntaric.openfhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syntaric.auth.ClientCredentialsTokenProvider;
import com.syntaric.openfhir.aql.ToAqlRequest;
import com.syntaric.openfhir.aql.ToAqlResponse;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

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

    public OpenFhirClient(final OpenFhirProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newHttpClient();
        this.fhirContext = FhirContext.forR4();
        this.objectMapper = new ObjectMapper();
        this.tokenProvider = properties.getOauth2().isConfigured()
                ? new ClientCredentialsTokenProvider(properties.getOauth2())
                : null;
    }

    public FhirContext getFhirContext() {
        return fhirContext;
    }

    public String convert(final IBaseResource resource) {
        final IParser parser = fhirContext.newJsonParser();
        final String fhirPayload = parser.encodeResourceToString(resource);

        final HttpRequest request = newRequestBuilder(TO_OPENFHIR_PATH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(fhirPayload))
                .build();

        try {
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("OpenFHIR conversion failed with status " + response.statusCode() + ": " + response.body());
            }
            log.info("OpenFHIR conversion successful, status={}", response.statusCode());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenFHIR conversion request interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("OpenFHIR conversion request failed", e);
        }
    }

    public ToAqlResponse getAql(final ToAqlRequest toAqlRequest) {
        try {
            final String payload = objectMapper.writeValueAsString(toAqlRequest);

            final HttpRequest request = newRequestBuilder(TO_AQL_PATH)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("OpenFHIR toAql failed with status " + response.statusCode() + ": " + response.body());
            }
            log.info("OpenFHIR toAql successful, status={}", response.statusCode());
            return objectMapper.readValue(response.body(), ToAqlResponse.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenFHIR toAql request interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("OpenFHIR toAql request failed", e);
        }
    }

    public String toFhir(final String openEhrQueryResult) {
        try {
            final HttpRequest request = newRequestBuilder(TO_FHIR_PATH + "?templateId=International+Patient+Summary")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(openEhrQueryResult))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("OpenFHIR toFhir failed with status " + response.statusCode() + ": " + response.body());
            }
            log.info("OpenFHIR toFhir successful, status={}", response.statusCode());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenFHIR toFhir request interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("OpenFHIR toFhir request failed", e);
        }
    }

    /**
     * Calls toFhir with a pre-assembled array of openEHR archetype nodes.
     */
    public String toFhir(final List<JsonNode> archetypeRows) {
        try {
            final String payload = objectMapper.writeValueAsString(archetypeRows);
            return toFhir(payload);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to serialize archetype rows for toFhir", e);
        }
    }

    private HttpRequest.Builder newRequestBuilder(final String path) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + path));
        if (tokenProvider != null) {
            builder.header("Authorization", "Bearer " + tokenProvider.getToken());
        }
        return builder;
    }
}
