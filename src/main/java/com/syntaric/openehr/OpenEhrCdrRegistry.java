package com.syntaric.openehr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves an {@link OpenEhrCdrClient} for the CDR instance named by the
 * {@code X-OpenEhrCdr} request header.
 *
 * <p>CDR instances are configured via {@link OpenEhrCdrProperties}:
 * <pre>
 * openehr.cdrs.default.base-url=http://localhost:8081
 * openehr.cdrs.hospital-a.base-url=http://hospital-a:8081
 * </pre>
 *
 * <p>When the header is absent or names an unknown CDR the first configured instance is used
 * and a warning is logged.
 */
@Component
@Slf4j
public class OpenEhrCdrRegistry {

    public static final String TARGET_CDR_HEADER = "X-OpenEhrCdr";

    private final Map<String, OpenEhrCdrClient> clients;
    private final String firstKey;
    private final OpenEhrCdrClient firstClient;

    public OpenEhrCdrRegistry(final OpenEhrCdrProperties properties) {
        if (properties.getCdrs() == null || properties.getCdrs().isEmpty()) {
            throw new IllegalStateException(
                    "No OpenEHR CDR instances configured. Define at least one entry under openehr.cdrs.*");
        }

        final Map<String, OpenEhrCdrClient> map = new LinkedHashMap<>();
        for (final Map.Entry<String, OpenEhrCdrProperties.CdrInstance> entry : properties.getCdrs().entrySet()) {
            final OpenEhrCdrProperties.CdrInstance cfg = entry.getValue();
            if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
                throw new IllegalStateException(
                        "OpenEHR CDR instance '" + entry.getKey() + "' has no base-url configured.");
            }
            log.info("Registered OpenEHR CDR '{}' → {} (oauth2={}, basicAuth={})", entry.getKey(), cfg.getBaseUrl(),
                    cfg.getOauth2().isConfigured(), cfg.getBasicAuth().isConfigured());
            map.put(entry.getKey(), new OpenEhrCdrClient(cfg.getBaseUrl(), cfg.getOauth2(), cfg.getBasicAuth()));
        }
        this.clients = map;

        final Map.Entry<String, OpenEhrCdrClient> first = map.entrySet().iterator().next();
        this.firstKey = first.getKey();
        this.firstClient = first.getValue();
    }

    /**
     * Returns the canonical CDR name that {@link #resolve(String)} would select.
     * Useful for storing the resolved name alongside identifiers.
     */
    public String resolveName(final String cdrName) {
        if (cdrName != null && !cdrName.isBlank() && clients.containsKey(cdrName)) {
            return cdrName;
        }
        return firstKey;
    }

    /**
     * Returns the {@link OpenEhrCdrClient} for the given CDR name.
     * Falls back to the first configured instance — with a warning — when {@code cdrName}
     * is null, blank, or does not match any configured CDR.
     */
    public OpenEhrCdrClient resolve(final String cdrName) {
        if (cdrName != null && !cdrName.isBlank()) {
            final OpenEhrCdrClient client = clients.get(cdrName);
            if (client != null) {
                return client;
            }
            log.warn("Unknown OpenEHR CDR '{}' requested via {} header; falling back to first instance '{}'",
                    cdrName, TARGET_CDR_HEADER, firstKey);
        } else {
            log.warn("{} header absent; falling back to first configured OpenEHR CDR instance '{}'",
                    TARGET_CDR_HEADER, firstKey);
        }
        return firstClient;
    }
}
