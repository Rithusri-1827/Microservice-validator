package com.msval.governance.engine.ops;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OpOutcome;
import com.msval.governance.engine.Operator;

/** F9 range — numeric bound check; absent bound = open (booleans are not numbers). */
public final class RangeOp implements Operator {

    @Override
    public OpOutcome evaluate(JsonNode v, JsonNode p, EvalContext ctx) {
        if (v == null || !v.isNumber()) {
            return OpOutcome.fail("not numeric: " + v);
        }
        BigDecimal lo = bound(p, "min");
        BigDecimal hi = bound(p, "max");
        BigDecimal val = v.decimalValue();
        boolean ok = (lo == null || val.compareTo(lo) >= 0) && (hi == null || val.compareTo(hi) <= 0);
        return ok ? OpOutcome.pass()
                : OpOutcome.fail(v.asText() + " outside [" + (lo == null ? "None" : lo) + ", "
                        + (hi == null ? "None" : hi) + "]");
    }

    private static BigDecimal bound(JsonNode p, String key) {
        JsonNode n = p == null ? null : p.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isNumber()) {
            // Python: comparing a number to a non-number raises TypeError → ENGINE_FAULT:BAD_PARAMS
            throw new IllegalArgumentException("range bound '" + key + "' is not numeric");
        }
        return n.decimalValue();
    }
}
