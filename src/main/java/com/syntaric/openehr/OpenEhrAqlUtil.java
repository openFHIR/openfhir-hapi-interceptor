package com.syntaric.openehr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class OpenEhrAqlUtil {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<JsonNode> extractArchetypeRows(final String aqlResultJson) {
        try {
            final JsonNode rows = objectMapper.readTree(aqlResultJson).path("rows");
            final List<JsonNode> result = new ArrayList<>();
            for (final JsonNode row : rows) {
                if (row.isArray() && !row.isEmpty()) {
                    result.add(row.get(0));
                }
            }
            return result;
        } catch (final Exception e) {
            throw new RuntimeException("Failed to parse AQL result rows: " + e.getMessage(), e);
        }
    }
}
