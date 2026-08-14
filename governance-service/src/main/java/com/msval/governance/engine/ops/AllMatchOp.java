package com.msval.governance.engine.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OpOutcome;
import com.msval.governance.engine.Operator;

/**
 * F9 all_match — target is the LIST itself (no [] fan-out); every object item must have
 * item[subkey] == equals (Python `==`; absent subkey counts as null and fails).
 */
public final class AllMatchOp implements Operator {

    @Override
    public OpOutcome evaluate(JsonNode v, JsonNode p, EvalContext ctx) {
        if (v == null || !v.isArray()) {
            return OpOutcome.fail("slice is not a list");
        }
        String subkey = Values.req(p, "subkey").asText();
        JsonNode equals = Values.req(p, "equals");
        int bad = 0;
        for (JsonNode item : v) {
            if (item.isObject() && !Values.pyEquals(Values.get(item, subkey), equals)) {
                bad++;
            }
        }
        return bad == 0 ? OpOutcome.pass()
                : OpOutcome.fail(bad + " item(s) fail " + subkey + "=" + equals);
    }
}
