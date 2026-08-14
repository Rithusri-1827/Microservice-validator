package com.msval.governance.engine.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Shared helpers for the graph operators. */
final class GraphSupport {

    private GraphSupport() {
    }

    /**
     * Python `graph.get(key, [])` followed by iteration: absent → empty list; present
     * but not a list → error (→ ENGINE_FAULT:BAD_PARAMS, as iterating would raise in Python).
     */
    static JsonNode listOrEmpty(JsonNode graph, String key) {
        if (!graph.has(key)) {
            return JsonNodeFactory.instance.arrayNode();
        }
        JsonNode n = graph.get(key);
        if (!n.isArray()) {
            throw new IllegalArgumentException("'" + key + "' is not a list");
        }
        return n;
    }
}
