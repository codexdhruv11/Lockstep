package com.lockstep.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

public final class JsonPointer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonPointer() {}

    public static Optional<String> extract(String json, String path) {
        if (json == null || json.isBlank() || path == null || path.isBlank()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            return Optional.empty();
        }
        for (String segment : segments(path)) {
            if (node == null || node.isMissingNode()) {
                return Optional.empty();
            }
            node = step(node, segment);
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(node.isValueNode() ? node.asText() : node.toString());
    }

    private static JsonNode step(JsonNode node, String segment) {
        int bracket = segment.indexOf('[');
        if (bracket < 0) {
            return node.path(segment);
        }
        String field = segment.substring(0, bracket);
        JsonNode current = field.isEmpty() ? node : node.path(field);
        String remainder = segment.substring(bracket);
        while (!remainder.isEmpty() && remainder.charAt(0) == '[') {
            int end = remainder.indexOf(']');
            if (end < 0) {
                return null;
            }
            try {
                current = current.path(Integer.parseInt(remainder.substring(1, end)));
            } catch (NumberFormatException e) {
                return null;
            }
            remainder = remainder.substring(end + 1);
        }
        return current;
    }

    private static String[] segments(String path) {
        String trimmed = path.trim();
        if (trimmed.startsWith("$.")) {
            trimmed = trimmed.substring(2);
        } else if (trimmed.startsWith("$")) {
            trimmed = trimmed.substring(1);
            if (trimmed.startsWith(".")) {
                trimmed = trimmed.substring(1);
            }
        }
        return trimmed.split("\\.");
    }
}
