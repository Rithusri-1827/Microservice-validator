package com.msval.governance.engine;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * F9 — one operator: (slice_value, params, ctx) → (passed, reason, detail).
 * Pure predicate. MISSING handling: only `exists` sees MISSING (the engine pre-fails others).
 * Exceptions escaping evaluate() become ENGINE_FAULT:BAD_PARAMS results.
 */
@FunctionalInterface
public interface Operator {

    OpOutcome evaluate(JsonNode slice, JsonNode params, EvalContext ctx);
}
