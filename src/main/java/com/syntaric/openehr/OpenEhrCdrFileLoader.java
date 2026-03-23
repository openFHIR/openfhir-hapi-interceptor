package com.syntaric.openehr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.syntaric.auth.BasicAuthConfig;
import com.syntaric.auth.ClientCredentialsConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * Reads the CDR registry from a YAML file on disk on every invocation — never cached.
 *
 * <p>Expected file format (flat list, no wrapper key):
 * <pre>
 * - id: local
 *   name: EHRbase (local)
 *   baseUrl: http://ehrbase:8080/ehrbase/rest
 *   endpointType: openehr
 *   authMethod: basic
 *   basicAuth:
 *     username: ehrbase-user
 *     password: secret
 *
 * - id: cadasto
 *   name: Cadasto
 *   baseUrl: https://cadasto.example.com
 *   endpointType: openehr
 *   authMethod: oauth2
 *   oauth2:
 *     tokenUrl: https://auth.example.com/token
 *     clientId: my-client
 *     clientSecret: my-secret
 * </pre>
 */
@Component
@Slf4j
public class OpenEhrCdrFileLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .findAndRegisterModules()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);

    private final String configFilePath;

    public OpenEhrCdrFileLoader(final OpenEhrCdrProperties properties) {
        this.configFilePath = properties.getCdrsConfigFile();
    }

    /**
     * Reads and returns the CDR list from disk. Never cached.
     *
     * @throws IllegalStateException if the file cannot be read or contains no entries
     */
    public List<CdrEntry> load() {
        if (configFilePath == null || configFilePath.isBlank()) {
            throw new IllegalStateException(
                    "openehr.cdrs-config-file is not set. Provide a path to the CDR config YAML file.");
        }

        final File file = new File(configFilePath);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalStateException("OpenEHR CDR config file not found: " + configFilePath);
        }

        try {
            final List<CdrEntry> entries = YAML_MAPPER.readValue(file, new TypeReference<>() {});
            if (entries == null || entries.isEmpty()) {
                throw new IllegalStateException(
                        "OpenEHR CDR config file contains no entries: " + configFilePath);
            }
            log.debug("Loaded {} CDR entries from {}", entries.size(), configFilePath);
            return entries;
        } catch (final IllegalStateException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "Failed to parse OpenEHR CDR config file '" + configFilePath + "': " + e.getMessage(), e);
        }
    }

    public enum EndpointType { FHIR, OPENEHR }
    public enum AuthMethod { NONE, BASIC, OAUTH2 }

    @Data
    public static class CdrEntry {
        private String id;
        private String name;
        private String baseUrl;
        private EndpointType endpointType;
        private AuthMethod authMethod;
        private ClientCredentialsConfig oauth2 = new ClientCredentialsConfig();
        private BasicAuthConfig basicAuth = new BasicAuthConfig();
    }
}
