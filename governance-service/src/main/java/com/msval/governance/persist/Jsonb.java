package com.msval.governance.persist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DD-017 helper — one shared mapper for jsonb round-trips. SQL passes JSON as text with
 * an explicit {@code ?::jsonb} cast (keeps every statement a plain prepared statement, NFR-004).
 */
public final class Jsonb {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Jsonb() {
    }

    public static String write(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        return n.toString();
    }

    public static JsonNode read(String s) {
        if (s == null) {
            return null;
        }
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable jsonb column: " + e.getMessage(), e);
        }
    }

    /** Postgres array literal from plain identifiers (stage ids, env names): each element quoted. */
    public static String textArray(Iterable<String> items) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String i : items) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(i.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
