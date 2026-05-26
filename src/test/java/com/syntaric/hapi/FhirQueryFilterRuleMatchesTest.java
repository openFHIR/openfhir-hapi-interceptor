package com.syntaric.hapi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class FhirQueryFilterRuleMatchesTest {

    // ruleMatches is package-private; we instantiate only the parts it needs
    private FhirQueryFilter filter;

    @BeforeEach
    void setUp() {
        // ruleMatches uses no injected dependencies — pass nulls for everything else
        filter = new FhirQueryFilter(null, null, null, null, null, new com.syntaric.InterceptorProperties());
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static com.syntaric.InterceptorProperties.Rule rule(final Map<String, String> fhirQuery) {
        final com.syntaric.InterceptorProperties.Rule rule = new com.syntaric.InterceptorProperties.Rule();
        rule.setFhirQuery(new LinkedHashMap<>(fhirQuery));
        return rule;
    }

    private static MockHttpServletRequest request(final Map<String, String> params) {
        final MockHttpServletRequest req = new MockHttpServletRequest();
        params.forEach(req::addParameter);
        return req;
    }

    // -------------------------------------------------------------------------
    // _resourceType matching
    // -------------------------------------------------------------------------

    @Test
    void matchesWhenResourceTypeEquals() {
        final var r = rule(Map.of("_resourceType", "AllergyIntolerance"));
        assertTrue(filter.ruleMatches(r, request(Map.of()), "AllergyIntolerance"));
    }

    @Test
    void doesNotMatchWhenResourceTypeDiffers() {
        final var r = rule(Map.of("_resourceType", "AllergyIntolerance"));
        assertFalse(filter.ruleMatches(r, request(Map.of()), "Condition"));
    }

    @Test
    void doesNotMatchWhenResourceTypeIsNull() {
        final var r = rule(Map.of("_resourceType", "AllergyIntolerance"));
        assertFalse(filter.ruleMatches(r, request(Map.of()), null));
    }

    // -------------------------------------------------------------------------
    // query parameter matching
    // -------------------------------------------------------------------------

    @Test
    void matchesWhenQueryParamPresent() {
        final var r = rule(Map.of("category", "laboratory"));
        assertTrue(filter.ruleMatches(r, request(Map.of("category", "laboratory")), null));
    }

    @Test
    void doesNotMatchWhenQueryParamValueDiffers() {
        final var r = rule(Map.of("category", "laboratory"));
        assertFalse(filter.ruleMatches(r, request(Map.of("category", "vital-signs")), null));
    }

    @Test
    void doesNotMatchWhenQueryParamAbsent() {
        final var r = rule(Map.of("category", "laboratory"));
        assertFalse(filter.ruleMatches(r, request(Map.of()), null));
    }

    // -------------------------------------------------------------------------
    // combined _resourceType + query params
    // -------------------------------------------------------------------------

    @Test
    void matchesWhenBothResourceTypeAndParamMatch() {
        final var r = rule(Map.of("_resourceType", "Observation", "category", "laboratory"));
        assertTrue(filter.ruleMatches(r, request(Map.of("category", "laboratory", "status", "current")), "Observation"));
    }

    @Test
    void doesNotMatchWhenResourceTypeMatchesButParamDiffers() {
        final var r = rule(Map.of("_resourceType", "Observation", "category", "laboratory"));
        assertFalse(filter.ruleMatches(r, request(Map.of("category", "vital-signs")), "Observation"));
    }

    @Test
    void doesNotMatchWhenParamMatchesButResourceTypeDiffers() {
        final var r = rule(Map.of("_resourceType", "Observation", "category", "laboratory"));
        assertFalse(filter.ruleMatches(r, request(Map.of("category", "laboratory")), "Condition"));
    }

    // -------------------------------------------------------------------------
    // multiple query params — all must match
    // -------------------------------------------------------------------------

    @Test
    void matchesWhenAllMultipleParamsPresent() {
        final var r = rule(Map.of("category", "laboratory", "status", "final"));
        assertTrue(filter.ruleMatches(r, request(Map.of("category", "laboratory", "status", "final")), null));
    }

    @Test
    void doesNotMatchWhenOneOfMultipleParamsMissing() {
        final var r = rule(Map.of("category", "laboratory", "status", "final"));
        assertFalse(filter.ruleMatches(r, request(Map.of("category", "laboratory")), null));
    }

    @Test
    void doesNotMatchWhenOneOfMultipleParamsDiffers() {
        final var r = rule(Map.of("category", "laboratory", "status", "final"));
        assertFalse(filter.ruleMatches(r, request(Map.of("category", "laboratory", "status", "preliminary")), null));
    }

    // -------------------------------------------------------------------------
    // empty rule — matches everything
    // -------------------------------------------------------------------------

    @Test
    void emptyRuleMatchesAnyRequest() {
        final var r = rule(Map.of());
        assertTrue(filter.ruleMatches(r, request(Map.of()), null));
        assertTrue(filter.ruleMatches(r, request(Map.of("category", "laboratory")), "Observation"));
    }

    // -------------------------------------------------------------------------
    // extra request params beyond rule criteria are irrelevant
    // -------------------------------------------------------------------------

    @Test
    void extraRequestParamsDoNotPreventMatch() {
        final var r = rule(Map.of("_resourceType", "Observation"));
        assertTrue(filter.ruleMatches(r, request(Map.of("status", "final", "patient", "123")), "Observation"));
    }

    // -------------------------------------------------------------------------
    // wildcard value "*"
    // -------------------------------------------------------------------------

    @Test
    void wildcardMatchesAnyResourceType() {
        final var r = rule(Map.of("_resourceType", "*"));
        assertTrue(filter.ruleMatches(r, request(Map.of()), "Observation"));
        assertTrue(filter.ruleMatches(r, request(Map.of()), "AllergyIntolerance"));
        assertTrue(filter.ruleMatches(r, request(Map.of()), "Condition"));
    }

    @Test
    void wildcardDoesNotMatchNullResourceType() {
        final var r = rule(Map.of("_resourceType", "*"));
        assertFalse(filter.ruleMatches(r, request(Map.of()), null));
    }

    @Test
    void wildcardMatchesAnyParamValue() {
        final var r = rule(Map.of("category", "*"));
        assertTrue(filter.ruleMatches(r, request(Map.of("category", "laboratory")), null));
        assertTrue(filter.ruleMatches(r, request(Map.of("category", "vital-signs")), null));
        assertTrue(filter.ruleMatches(r, request(Map.of("category", "anything")), null));
    }

    @Test
    void wildcardDoesNotMatchAbsentParam() {
        final var r = rule(Map.of("category", "*"));
        assertFalse(filter.ruleMatches(r, request(Map.of()), null));
    }

    @Test
    void wildcardParamCombinedWithExactResourceType() {
        final var r = rule(Map.of("_resourceType", "Observation", "category", "*"));
        assertTrue(filter.ruleMatches(r, request(Map.of("category", "laboratory")), "Observation"));
        assertTrue(filter.ruleMatches(r, request(Map.of("category", "vital-signs")), "Observation"));
        assertFalse(filter.ruleMatches(r, request(Map.of("category", "laboratory")), "Condition"));
        assertFalse(filter.ruleMatches(r, request(Map.of()), "Observation"));
    }

    @Test
    void wildcardResourceTypeCombinedWithExactParam() {
        final var r = rule(Map.of("_resourceType", "*", "status", "final"));
        assertTrue(filter.ruleMatches(r, request(Map.of("status", "final")), "Observation"));
        assertTrue(filter.ruleMatches(r, request(Map.of("status", "final")), "Condition"));
        assertFalse(filter.ruleMatches(r, request(Map.of("status", "preliminary")), "Observation"));
        assertFalse(filter.ruleMatches(r, request(Map.of("status", "final")), null));
    }
}
