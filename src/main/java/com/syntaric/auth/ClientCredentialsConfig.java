package com.syntaric.auth;

import lombok.Data;

/**
 * Configuration block for an OAuth2 client_credentials flow.
 * Embed this inside any properties class that needs per-instance auth.
 *
 * <pre>
 * *.oauth2.token-url=https://auth.example.com/oauth2/token
 * *.oauth2.client-id=my-client
 * *.oauth2.client-secret=secret
 * *.oauth2.scope=openid profile          # optional, space-separated
 * *.oauth2.auth-method=body              # body (default) or basic
 * *.oauth2.extra-params.audience=my-api  # optional additional body params
 * </pre>
 */
@Data
public class ClientCredentialsConfig {

    public enum AuthMethod { BODY, BASIC }

    private String tokenUrl;
    private String clientId;
    private String clientSecret;
    /** Optional space-separated scope string added as the {@code scope} body parameter. */
    private String scope;
    /**
     * How to transmit {@code client_id} and {@code client_secret} to the token endpoint.
     * {@code BODY} (default) — included as form body parameters.
     * {@code BASIC} — sent as an HTTP Basic Authorization header (required by some providers, e.g. Cadasto).
     */
    private AuthMethod authMethod = AuthMethod.BODY;
    /** Any additional key=value pairs to include in the token request body. */
    private java.util.Map<String, String> extraParams = new java.util.LinkedHashMap<>();

    public boolean isConfigured() {
        return tokenUrl != null && !tokenUrl.isBlank()
                && clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }
}
