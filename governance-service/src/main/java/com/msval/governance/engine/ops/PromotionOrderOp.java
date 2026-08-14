package com.msval.governance.engine.ops;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OpOutcome;
import com.msval.governance.engine.Operator;

/**
 * MOD-007 (DD-007) — promotion_order: environment progression vs governance history.
 * Mirror of msval.core.automata.op_promotion_order. Failure reason: ILLEGAL_PROMOTION.
 */
public final class PromotionOrderOp implements Operator {

    @Override
    public OpOutcome evaluate(JsonNode v, JsonNode p, EvalContext ctx) {
        JsonNode orderNode = Values.req(p, "order");
        if (!orderNode.isArray()) {
            throw new IllegalArgumentException("params.order is not a list");
        }
        List<String> order = new ArrayList<>();
        for (JsonNode o : orderNode) {
            order.add(o.asText());
        }
        Set<String> skips = new HashSet<>();
        JsonNode skipsNode = p.get("allowed_skips");
        if (skipsNode != null && skipsNode.isArray()) {
            for (JsonNode skip : skipsNode) {
                skips.add(skip.asText());
            }
        }
        String env = v != null && v.isTextual() ? v.textValue() : ctx.environment();
        int idx = order.indexOf(env);
        if (idx < 0) {
            return OpOutcome.fail("ILLEGAL_PROMOTION",
                    "environment '" + env + "' not in promotion order " + order);
        }
        if (idx == 0) {
            return OpOutcome.pass();
        }
        String prereq = order.get(idx - 1);
        List<JsonNode> history = ctx.promotionHistory() != null ? ctx.promotionHistory() : List.of();
        boolean validated = history.stream().anyMatch(h -> h != null && h.isObject()
                && prereq.equals(h.path("env").asText(null))
                && "Validated".equals(h.path("state").asText(null)));
        if (skips.contains(prereq) || validated) {
            return OpOutcome.pass();
        }
        return OpOutcome.fail("ILLEGAL_PROMOTION",
                "version not Validated in prerequisite environment '" + prereq + "'");
    }
}
