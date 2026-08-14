package com.msval.governance.engine.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OpOutcome;
import com.msval.governance.engine.Operator;

/** F9 in_set — membership with Python `==` semantics. */
public final class InSetOp implements Operator {

    @Override
    public OpOutcome evaluate(JsonNode v, JsonNode p, EvalContext ctx) {
        JsonNode values = Values.req(p, "values");
        if (!values.isArray()) {
            throw new IllegalArgumentException("params.values is not a list");
        }
        for (JsonNode item : values) {
            if (Values.pyEquals(v, item)) {
                return OpOutcome.pass();
            }
        }
        return OpOutcome.fail(v + " not in approved set");
    }
}
