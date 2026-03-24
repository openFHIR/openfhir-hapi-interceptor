package com.syntaric.openehr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves an {@link OpenEhrCdrClient} for the CDR instance named by the
 * {@code X-OpenEhrCdr} request header.
 *
 * <p>CDR instances are loaded from a YAML file on disk on every call via
 * {@link OpenEhrCdrFileLoader} — no caching, so external changes take effect immediately.
 */
@Component
@Slf4j
public class OpenEhrCdrRegistry {

    public static final String TARGET_CDR_HEADER = "X-OpenEhrCdr";

    private final OpenEhrCdrFileLoader fileLoader;

    public OpenEhrCdrRegistry(final OpenEhrCdrFileLoader fileLoader) {
        this.fileLoader = fileLoader;
    }

    /**
     * Returns the canonical CDR id that {@link #resolve(String)} would select.
     */
    public String resolveName(final String cdrId) {
        final List<OpenEhrCdrFileLoader.CdrEntry> entries = fileLoader.load();
        if (cdrId != null && !cdrId.isBlank()) {
            for (final OpenEhrCdrFileLoader.CdrEntry entry : entries) {
                if (cdrId.equals(entry.getId())) {
                    return cdrId;
                }
            }
        }
        return entries.get(0).getId();
    }

    /**
     * Returns a fresh {@link OpenEhrCdrClient} built from the current file state.
     * Falls back to the first entry when {@code cdrId} is null, blank, or unknown.
     */
    public OpenEhrCdrClient resolve(final String cdrId) {
        final OpenEhrCdrFileLoader.CdrEntry match = resolveEntry(cdrId);
        return new OpenEhrCdrClient(match.getBaseUrl(), match.getOauth2(), match.getBasicAuth());
    }

    /**
     * Returns the {@link OpenEhrCdrFileLoader.CdrEntry} for the given CDR id.
     * Falls back to the first entry when {@code cdrId} is null, blank, or unknown.
     */
    public OpenEhrCdrFileLoader.CdrEntry resolveEntry(final String cdrId) {
        final List<OpenEhrCdrFileLoader.CdrEntry> entries = fileLoader.load();

        OpenEhrCdrFileLoader.CdrEntry match = null;

        if (cdrId != null && !cdrId.isBlank()) {
            for (final OpenEhrCdrFileLoader.CdrEntry entry : entries) {
                if (cdrId.equals(entry.getId())) {
                    match = entry;
                    break;
                }
            }
            if (match == null) {
                log.warn("Unknown CDR '{}' requested via {} header; falling back to first entry",
                        cdrId, TARGET_CDR_HEADER);
            }
        } else {
            log.warn("{} header absent; falling back to first configured CDR entry", TARGET_CDR_HEADER);
        }

        if (match == null) {
            match = entries.get(0);
        }

        log.debug("Resolved CDR '{}' → {}", match.getId(), match.getBaseUrl());
        return match;
    }
}
