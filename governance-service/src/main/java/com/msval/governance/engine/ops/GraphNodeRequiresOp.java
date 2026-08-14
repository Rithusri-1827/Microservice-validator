package com.msval.governance.engine.ops;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OpOutcome;
import com.msval.governance.engine.Operator;

/**
 * F9 graph_node_requires — graph is the object slice if it is one, else ctx.topology;
 * every node with if_attr == if_equals must have require_attr == require_equals.
 */
public final class GraphNodeRequiresOp implements Operator {

    @Override
    public OpOutcome evaluate(JsonNode v, JsonNode p, EvalContext ctx) {
        JsonNode graph = v != null && v.isObject() ? v
                : (ctx.topology() != null ? ctx.topology() : JsonNodeFactory.instance.objectNode());
        String ifAttr = Values.req(p, "if_attr").asText();
        JsonNode ifEquals = Values.req(p, "if_equals");
        String requireAttr = Values.req(p, "require_attr").asText();
        JsonNode requireEquals = Values.req(p, "require_equals");
        JsonNode nodes = GraphSupport.listOrEmpty(graph, "nodes");
        List<String> bad = new ArrayList<>();
        for (JsonNode n : nodes) {
            if (!n.isObject()) {
                throw new IllegalArgumentException("node is not an object: " + n);
            }
            if (Values.pyEquals(Values.get(n, ifAttr), ifEquals)
                    && !Values.pyEquals(Values.get(n, requireAttr), requireEquals)) {
                bad.add(Values.pyStr(Values.get(n, "id")));
            }
        }
        return bad.isEmpty() ? OpOutcome.pass()
                : OpOutcome.fail("nodes failing requirement: " + bad);
    }
}
