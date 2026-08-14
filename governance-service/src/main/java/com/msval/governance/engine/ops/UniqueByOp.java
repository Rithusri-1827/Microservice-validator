package com.msval.governance.engine.ops;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OpOutcome;
import com.msval.governance.engine.Operator;

/**
 * F9 unique_by — target is the LIST itself; at most one distinct non-null str(subkey value)
 * across object items (mirror of the Python set-of-str semantics).
 */
public final class UniqueByOp implements Operator {

    @Override
    public OpOutcome evaluate(JsonNode v, JsonNode p, EvalContext ctx) {
        if (v == null || !v.isArray()) {
            return OpOutcome.fail("slice is not a list");
        }
        String subkey = Values.req(p, "subkey").asText();
        Set<String> vals = new LinkedHashSet<>();
        for (JsonNode item : v) {
            if (item.isObject()) {
                JsonNode value = Values.get(item, subkey);
                if (!value.isNull()) {
                    vals.add(Values.pyStr(value));
                }
            }
        }
        boolean ok = vals.size() <= 1;
        return ok ? OpOutcome.pass()
                : OpOutcome.fail("multiple " + subkey + " values: " + new TreeSet<>(vals));
    }
}
