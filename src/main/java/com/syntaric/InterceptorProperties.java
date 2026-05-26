package com.syntaric;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single authoritative {@code @ConfigurationProperties} bean for the {@code interceptor} prefix.
 * Consolidates CDR config, FHIR create filter, and FHIR query filter settings to avoid
 * Spring Boot's silent empty-binding issue with multiple beans sharing the same prefix root.
 *
 * <pre>
 * interceptor:
 *   cdrs-config-file: /etc/cdrs.yml
 *   fhir-create-filter:
 *     intercepted-profiles:
 *       - http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips
 *   fhir-query-filter:
 *     rules:
 *       - template-id: International Patient Summary
 *         fhir-query:
 *           _resourceType: AllergyIntolerance
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "interceptor")
@Data
public class InterceptorProperties {

    /**
     * Path on disk to the YAML file containing the CDR registry.
     * Re-read on every request; no restart required when the file changes.
     */
    private String cdrsConfigFile;

    private FhirCreateFilter fhirCreateFilter = new FhirCreateFilter();
    private FhirQueryFilter fhirQueryFilter = new FhirQueryFilter();
    private Ips ips = new Ips();

    @Data
    public static class Ips {
        /** openEHR template ID used by the IPS $summary operation. */
        private String templateId = "International Patient Summary";
    }

    @Data
    public static class FhirCreateFilter {
        /**
         * Composition profile URLs that should be intercepted and forwarded to OpenEHR.
         * Defaults to the IPS Composition profile.
         */
        private List<String> interceptedProfiles = new ArrayList<>(List.of(
                "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips"
        ));
    }

    @Data
    public static class FhirQueryFilter {
        private List<Rule> rules = new ArrayList<>();
    }

    @Data
    public static class Rule {

        /**
         * FHIR query parameters that must all be present on the request for this rule to match.
         * Use the special key {@code _resourceType} to match the resource type in the URI path.
         */
        private Map<String, String> fhirQuery = new LinkedHashMap<>();
    }
}
