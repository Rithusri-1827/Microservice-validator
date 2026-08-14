package com.msval.governance.engine.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OpOutcome;
import com.msval.governance.engine.Operator;

/** F9 equals — type-strict equality (`slice == value and type(slice) is type(value)`). */
public final class EqualsOp implements Operator {

    @Override
    public OpOutcome evaluate(JsonNode v, JsonNode p, EvalContext ctx) {
        JsonNode expected = Values.req(p, "value");
        boolean ok = Values.typeStrictEquals(v, expected);
        return ok ? OpOutcome.pass() : OpOutcome.fail("expected " + expected + ", got " + v);
    }
}
