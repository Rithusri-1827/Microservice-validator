package com.msval.governance.engine.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OpOutcome;
import com.msval.governance.engine.Operator;

/**
 * F9 none_match_if — target is the LIST itself; no object item with if_key == if_equals
 * may have then_key == forbidden (Python `==`).
 */
public final class NoneMatchIfOp implements Operator {

    @Override
    public OpOutcome evaluate(JsonNode v, JsonNode p, EvalContext ctx) {
        if (v == null || !v.isArray()) {
            return OpOutcome.fail("slice is not a list");
        }
        String ifKey = Values.req(p, "if_key").asText();
        JsonNode ifEquals = Values.req(p, "if_equals");
        String thenKey = Values.req(p, "then_key").asText();
        JsonNode forbidden = Values.req(p, "forbidden");
        int bad = 0;
        for (JsonNode item : v) {
            if (item.isObject()
                    && Values.pyEquals(Values.get(item, ifKey), ifEquals)
                    && Values.pyEquals(Values.get(item, thenKey), forbidden)) {
                bad++;
            }
        }
        return bad == 0 ? OpOutcome.pass() : OpOutcome.fail(bad + " forbidden combination(s)");
    }
}
