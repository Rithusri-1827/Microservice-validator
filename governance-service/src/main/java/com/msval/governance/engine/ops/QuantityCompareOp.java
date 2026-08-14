package com.msval.governance.engine.ops;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.cdm.PathResolver;
import com.msval.governance.cdm.Quantity;
import com.msval.governance.cdm.QuantityException;
import com.msval.governance.cdm.Slice;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OpOutcome;
import com.msval.governance.engine.Operator;

/**
 * F9 quantity_compare — slice is an object; params.left resolved relative to the slice (F2),
 * dimensions tried memory-then-cpu (right_expr must parse in the same dimension),
 * unparseable ⇒ RULE_FAILED. Canonical-integer comparison per F3.
 */
public final class QuantityCompareOp implements Operator {

    @Override
    public OpOutcome evaluate(JsonNode v, JsonNode p, EvalContext ctx) {
        if (v == null || !v.isObject()) {
            return OpOutcome.fail("slice is not an object");
        }
        String leftPath = Values.req(p, "left").asText();
        String rightExpr = Values.req(p, "right_expr").asText();
        String op = Values.req(p, "op").asText();

        List<Slice> slices = PathResolver.resolve(v, leftPath);
        if (slices.isEmpty() || slices.get(0).value().isMissingNode()) {
            return OpOutcome.fail("left path " + leftPath + " missing");
        }
        JsonNode leftRaw = slices.get(0).value();
        String leftText = leftRaw.isTextual() ? leftRaw.textValue() : leftRaw.asText();

        Long left = null;
        Long right = null;
        for (String dim : new String[] {"memory", "cpu"}) {
            try {
                long l = Quantity.parse(leftText, dim);
                long r = Quantity.parseExpr(rightExpr, dim);
                left = l;
                right = r;
                break;
            } catch (QuantityException e) {
                // try next dimension
            }
        }
        if (left == null) {
            return OpOutcome.fail("unparseable quantities '" + leftText + "' vs '" + rightExpr + "'");
        }
        boolean ok = switch (op) {
            case "gte" -> left >= right;
            case "lte" -> left <= right;
            case "gt" -> left > right;
            case "lt" -> left < right;
            default -> throw new IllegalArgumentException("KeyError: '" + op + "'"); // → ENGINE_FAULT:BAD_PARAMS
        };
        return ok ? OpOutcome.pass()
                : OpOutcome.fail(leftText + " " + op + " " + rightExpr + " is false");
    }
}
