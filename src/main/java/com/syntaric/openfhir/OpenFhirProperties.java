package com.syntaric.openfhir;

import com.syntaric.auth.ClientCredentialsConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the OpenFHIR mapping service.
 *
 * <pre>
 * openfhir.base-url=https://sandbox.open-fhir.com
 *
 * # optional OAuth2 client_credentials
 * openfhir.oauth2.token-url=https://auth.example.com/oauth2/token
 * openfhir.oauth2.client-id=my-client
 * openfhir.oauth2.client-secret=secret
 * openfhir.oauth2.scope=openid
 * openfhir.oauth2.extra-params.audience=openfhir-api
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "openfhir")
@Data
public class OpenFhirProperties {

    private String baseUrl;
    private ClientCredentialsConfig oauth2 = new ClientCredentialsConfig();
}
