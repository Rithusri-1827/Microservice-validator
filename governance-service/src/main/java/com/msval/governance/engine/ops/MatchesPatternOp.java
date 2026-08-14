package com.msval.governance.engine.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OpOutcome;
import com.msval.governance.engine.Operator;

/**
 * F9 matches_pattern — full match (java.util.regex matches()). Patterns are pre-linted
 * RE2-safe at publish time; a compile failure here escapes as ENGINE_FAULT:BAD_PARAMS.
 * Bounded LRU cache per DD-006/009 implementation notes.
 */
public final class MatchesPatternOp implements Operator {

    private static final int CACHE_SIZE = 512;

    private final Map<String, Pattern> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    @Override
    public OpOutcome evaluate(JsonNode v, JsonNode p, EvalContext ctx) {
        if (v == null || !v.isTextual()) {
            return OpOutcome.fail("not a string: " + v);
        }
        String patternText = Values.req(p, "pattern").asText();
        Pattern pattern;
        synchronized (cache) {
            pattern = cache.computeIfAbsent(patternText, Pattern::compile);
        }
        boolean ok = pattern.matcher(v.textValue()).matches();
        return ok ? OpOutcome.pass()
                : OpOutcome.fail("'" + v.textValue() + "' does not match '" + patternText + "'");
    }
}
