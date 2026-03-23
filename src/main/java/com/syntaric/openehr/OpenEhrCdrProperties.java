package com.syntaric.openehr;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds OpenEHR configuration from application.yml.
 *
 * <pre>
 * openehr:
 *   cdrs-config-file: /etc/openfhir/cdrs.yml
 * </pre>
 *
 * CDR instances are read from the file at {@code cdrs-config-file} on every request
 * by {@link OpenEhrCdrFileLoader} — no caching, no restart required on file changes.
 */
@Component
@ConfigurationProperties(prefix = "openehr")
@Data
public class OpenEhrCdrProperties {

    /**
     * Path on disk to the YAML file containing the CDR registry.
     * Re-read on every request; no restart required when the file changes.
     */
    private String cdrsConfigFile;
}
