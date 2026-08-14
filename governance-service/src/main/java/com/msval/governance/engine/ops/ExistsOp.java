package com.msval.governance.engine.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OpOutcome;
import com.msval.governance.engine.Operator;

/** F9 exists — the only operator that sees MISSING; passes iff value is neither MISSING nor null. */
public final class ExistsOp implements Operator {

    @Override
    public OpOutcome evaluate(JsonNode v, JsonNode p, EvalContext ctx) {
        boolean ok = v != null && !v.isMissingNode() && !v.isNull();
        return ok ? OpOutcome.pass() : OpOutcome.fail("path missing or null");
    }
}
