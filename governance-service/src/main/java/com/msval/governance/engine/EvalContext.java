package com.msval.governance.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * F10 — evaluation context (DD-006). Pure data, no I/O; {@code now} is injected for determinism.
 * Mirrors msval.core.engine.types.EvalContext (defaults included).
 */
public record EvalContext(
        String phase,
        String environment,
        Map<String, Long> capacity,
        List<JsonNode> promotionHistory,
        JsonNode liveState,
        JsonNode declaredBaseline,
        JsonNode topology,
        List<Waiver> waivers,
        String now) {

    public static final String DEFAULT_NOW = "1970-01-01T00:00:00Z";

    public static EvalContext defaults() {
        return new EvalContext("ci", "test", null, null, null, null, null, List.of(), DEFAULT_NOW);
    }

    /** Mirror of EvalContext.from_dict: unknown keys ignored, waivers materialized, defaults applied. */
    public static EvalContext fromJson(JsonNode d) {
        if (d == null || d.isNull() || d.isMissingNode()) {
            return defaults();
        }
        String phase = textOr(d, "phase", "ci");
        String environment = textOr(d, "environment", "test");
        Map<String, Long> capacity = null;
        if (d.hasNonNull("capacity") && d.get("capacity").isObject()) {
            capacity = new LinkedHashMap<>();
            var it = d.get("capacity").fields();
            while (it.hasNext()) {
                var e = it.next();
                capacity.put(e.getKey(), e.getValue().asLong());
            }
        }
        List<JsonNode> history = null;
        if (d.hasNonNull("promotion_history") && d.get("promotion_history").isArray()) {
            history = new ArrayList<>();
            for (JsonNode h : d.get("promotion_history")) {
                history.add(h);
            }
        }
        JsonNode liveState = nodeOrNull(d, "live_state");
        JsonNode declaredBaseline = nodeOrNull(d, "declared_baseline");
        JsonNode topology = nodeOrNull(d, "topology");
        List<Waiver> waivers = new ArrayList<>();
        if (d.hasNonNull("waivers") && d.get("waivers").isArray()) {
            for (JsonNode w : d.get("waivers")) {
                waivers.add(new Waiver(
                        w.path("waiver_id").asLong(0),
                        w.path("rule_id").asText(""),
                        w.path("service_id").asText(""),
                        w.path("version").asText(""),
                        w.path("environment").asText(""),
                        w.path("expires_at").asText("")));
            }
        }
        String now = textOr(d, "now", DEFAULT_NOW);
        return new EvalContext(phase, environment, capacity, history, liveState, declaredBaseline,
                topology, List.copyOf(waivers), now);
    }

    private static String textOr(JsonNode d, String key, String fallback) {
        JsonNode n = d.get(key);
        return n == null || n.isNull() ? fallback : n.asText();
    }

    private static JsonNode nodeOrNull(JsonNode d, String key) {
        JsonNode n = d.get(key);
        return n == null || n.isNull() ? null : n;
    }
}
