package com.syntaric.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Fetches and caches an OAuth2 {@code client_credentials} access token for a single
 * configured endpoint. Thread-safe; the token is refreshed automatically when it
 * expires (with a 30-second safety margin).
 */
@Slf4j
public class ClientCredentialsTokenProvider {

    private static final int EXPIRY_MARGIN_SECONDS = 30;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ClientCredentialsConfig config;

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public ClientCredentialsTokenProvider(final ClientCredentialsConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns a valid Bearer token, fetching a new one when the cached token is
     * absent or within {@value EXPIRY_MARGIN_SECONDS} seconds of expiring.
     */
    public synchronized String getToken() {
        if (cachedToken == null || Instant.now().isAfter(tokenExpiry.minusSeconds(EXPIRY_MARGIN_SECONDS))) {
            fetchToken();
        }
        return cachedToken;
    }

    private void fetchToken() {
        log.info("Fetching client_credentials token from {} (auth-method={})",
                config.getTokenUrl(), config.getAuthMethod());

        final Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "client_credentials");

        final boolean basic = config.getAuthMethod() == ClientCredentialsConfig.AuthMethod.BASIC;
        if (!basic) {
            params.put("client_id", config.getClientId());
            params.put("client_secret", config.getClientSecret());
        }
        if (config.getScope() != null && !config.getScope().isBlank()) {
            params.put("scope", config.getScope());
        }
        if (config.getExtraParams() != null) {
            params.putAll(config.getExtraParams());
        }

        final String body = encodeFormBody(params);

        final HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.getTokenUrl()))
                .header("Content-Type", "application/x-www-form-urlencoded");

        if (basic) {
            final String credentials = config.getClientId() + ":" + config.getClientSecret();
            final String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        }

        final HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Token request failed with status " + response.statusCode() + ": " + response.body());
            }

            final JsonNode json = objectMapper.readTree(response.body());
            cachedToken = json.get("access_token").asText();
            final long expiresIn = json.has("expires_in") ? json.get("expires_in").asLong(3600) : 3600L;
            tokenExpiry = Instant.now().plusSeconds(expiresIn);

            log.info("Token acquired, expires in {}s", expiresIn);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Token request interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Token request failed", e);
        }
    }

    private static String encodeFormBody(final Map<String, String> params) {
        final StringJoiner joiner = new StringJoiner("&");
        for (final Map.Entry<String, String> entry : params.entrySet()) {
            joiner.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return joiner.toString();
    }
}
