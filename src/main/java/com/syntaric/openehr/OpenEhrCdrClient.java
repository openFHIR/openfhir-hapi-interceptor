package com.syntaric.openehr;

import ca.uhn.fhir.util.StringUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syntaric.auth.BasicAuthConfig;
import com.syntaric.auth.ClientCredentialsConfig;
import com.syntaric.auth.ClientCredentialsTokenProvider;
import com.syntaric.openfhir.aql.AqlRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HTTP executor for a single OpenEHR CDR instance.
 * Instances are created and managed by {@link OpenEhrCdrRegistry}; this class
 * is intentionally not a Spring bean.
 */
@Slf4j
public class OpenEhrCdrClient {

    private static final String AQL_PATH = "/openehr/v1/query/aql";
    private static final String STORE_PATH = "/openehr/v1/ehr/%s/composition";
    private static final String CREATE_EHR_PATH = "/openehr/v1/ehr";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final ClientCredentialsTokenProvider tokenProvider;
    private final String basicAuthHeader;

    public OpenEhrCdrClient(final String baseUrl, final ClientCredentialsConfig oauth2, final BasicAuthConfig basicAuth) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.tokenProvider = (oauth2 != null && oauth2.isConfigured())
                ? new ClientCredentialsTokenProvider(oauth2)
                : null;
        this.basicAuthHeader = (tokenProvider == null && basicAuth != null && basicAuth.isConfigured())
                ? "Basic " + Base64.getEncoder().encodeToString(
                        (basicAuth.getUsername() + ":" + basicAuth.getPassword()).getBytes(StandardCharsets.UTF_8))
                : null;
    }

    /**
     * Creates a new EHR record and returns the EHR ID extracted from the {@code Location} response
     * header (last path segment of the URL returned by {@code POST /rest/openehr/v1/ehr}).
     *
     * @return the EHR ID string
     * @throws RuntimeException if the request fails or the Location header is absent
     */
    public String createEhr() {
        final HttpRequest request = newRequestBuilder(CREATE_EHR_PATH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OpenEhrCdrException("OpenEHR CDR EHR creation failed with status " + response.statusCode(),
                        response.statusCode(), response.body(), response.headers());
            }
            final String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new OpenEhrCdrException("OpenEHR CDR EHR creation response missing Location header",
                            response.statusCode(), response.body(), response.headers()));
            final String ehrId = location.substring(location.lastIndexOf('/') + 1);
            log.info("OpenEHR CDR EHR created, baseUrl={}, ehrId={}", baseUrl, ehrId);
            return ehrId;
        } catch (final OpenEhrCdrException e) {
            throw e;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenEhrCdrException("OpenEHR CDR EHR creation request interrupted", e);
        } catch (final Exception e) {
            throw new OpenEhrCdrException("OpenEHR CDR EHR creation request failed", e);
        }
    }

    /**
     * Stores an openEHR composition and returns the value of the {@code Location} response header,
     * which contains the URL of the newly created composition (including its uid).
     * Returns {@code null} if the CDR did not include a {@code Location} header.
     */
    public String store(final String openEhrPayload, final String ehrId) {
        final HttpRequest request = newRequestBuilder(String.format(STORE_PATH, ehrId))
                .header("Content-Type", "application/json")
                .header("Prefer", "return=representation")
                .POST(HttpRequest.BodyPublishers.ofString(openEhrPayload))
                .build();

        try {
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OpenEhrCdrException("OpenEHR CDR store failed with status " + response.statusCode(),
                        response.statusCode(), response.body(), response.headers());
            }
            final String location = response.headers().firstValue("Location").orElse(null);
            log.info("OpenEHR CDR store successful, baseUrl={}, status={}, location={}", baseUrl, response.statusCode(), location);
            return location;
        } catch (final OpenEhrCdrException e) {
            throw e;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenEhrCdrException("OpenEHR CDR store request interrupted", e);
        } catch (final Exception e) {
            throw new OpenEhrCdrException("OpenEHR CDR store request failed", e);
        }
    }

    public String queryAql(final String aql) {
        try {
            final String payload = objectMapper.writeValueAsString(new AqlRequest(aql));

            final HttpRequest request = newRequestBuilder(AQL_PATH)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OpenEhrCdrException("OpenEHR CDR AQL query failed with status " + response.statusCode(),
                        response.statusCode(), response.body(), response.headers());
            }
            log.info("OpenEHR CDR AQL query successful, baseUrl={}, status={}", baseUrl, response.statusCode());
            return response.body();
        } catch (final OpenEhrCdrException e) {
            throw e;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenEhrCdrException("OpenEHR CDR AQL query interrupted", e);
        } catch (final Exception e) {
            throw new OpenEhrCdrException("OpenEHR CDR AQL query failed", e);
        }
    }

    private HttpRequest.Builder newRequestBuilder(final String path) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path));
        if (tokenProvider != null) {
            builder.header("Authorization", "Bearer " + tokenProvider.getToken());
        } else if (basicAuthHeader != null) {
            builder.header("Authorization", basicAuthHeader);
        }
        return builder;
    }
}
