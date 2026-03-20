package com.syntaric.auth;

import lombok.Data;

/**
 * Configuration block for HTTP Basic authentication.
 * Embed this inside any properties class that needs per-instance auth.
 *
 * <pre>
 * *.basic-auth.username=alice
 * *.basic-auth.password=secret
 * </pre>
 */
@Data
public class BasicAuthConfig {

    private String username;
    private String password;

    public boolean isConfigured() {
        return username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }
}
