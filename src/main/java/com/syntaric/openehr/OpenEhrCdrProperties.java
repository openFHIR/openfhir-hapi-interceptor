package com.syntaric.openehr;

import com.syntaric.auth.BasicAuthConfig;
import com.syntaric.auth.ClientCredentialsConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds all OpenEHR CDR instance configuration from properties of the form:
 *
 * <pre>
 * openehr.cdrs.default.base-url=http://localhost:8081
 * openehr.cdrs.hospital-a.base-url=http://hospital-a:8081
 * </pre>
 *
 * The map key is the CDR name matched against the {@code X-OpenEhrCdr} request header.
 * A key named {@code default} is used when the header is absent or unrecognised.
 */
@Component
@ConfigurationProperties(prefix = "openehr")
@Data
public class OpenEhrCdrProperties {

    private Map<String, CdrInstance> cdrs = new LinkedHashMap<>();

    @Data
    public static class CdrInstance {
        private String baseUrl;
        /**
         * Optional OAuth2 client_credentials configuration for this CDR instance.
         * When {@code token-url}, {@code client-id} and {@code client-secret} are all set,
         * every request to this CDR will carry a Bearer token.
         */
        private ClientCredentialsConfig oauth2 = new ClientCredentialsConfig();
        /**
         * Optional HTTP Basic auth configuration for this CDR instance.
         * When {@code username} and {@code password} are set, every request will carry
         * a Basic Authorization header. Ignored when {@code oauth2} is also configured.
         */
        private BasicAuthConfig basicAuth = new BasicAuthConfig();
    }
}
