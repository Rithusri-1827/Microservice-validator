package com.msval.governance.engine.ops;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OpOutcome;
import com.msval.governance.engine.Operator;

/**
 * F9 graph_all_edges — graph is the object slice if it is one, else ctx.topology;
 * every entry of graph.connections must have edge[attr] == equals.
 */
public final class GraphAllEdgesOp implements Operator {

    @Override
    public OpOutcome evaluate(JsonNode v, JsonNode p, EvalContext ctx) {
        JsonNode graph = v != null && v.isObject() ? v
                : (ctx.topology() != null ? ctx.topology() : JsonNodeFactory.instance.objectNode());
        String attr = Values.req(p, "attr").asText();
        JsonNode equals = Values.req(p, "equals");
        JsonNode edges = GraphSupport.listOrEmpty(graph, "connections");
        List<String> bad = new ArrayList<>();
        for (JsonNode e : edges) {
            if (!e.isObject()) {
                throw new IllegalArgumentException("edge is not an object: " + e);
            }
            if (!Values.pyEquals(Values.get(e, attr), equals)) {
                bad.add(Values.pyStr(Values.get(e, "to")));
            }
        }
        return bad.isEmpty() ? OpOutcome.pass()
                : OpOutcome.fail("edges failing " + attr + ": " + bad);
    }
}
