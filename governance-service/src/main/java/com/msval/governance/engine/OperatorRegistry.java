package com.msval.governance.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.msval.governance.engine.ops.AllMatchOp;
import com.msval.governance.engine.ops.EqualsOp;
import com.msval.governance.engine.ops.ExistsOp;
import com.msval.governance.engine.ops.GraphAllEdgesOp;
import com.msval.governance.engine.ops.GraphNodeRequiresOp;
import com.msval.governance.engine.ops.InSetOp;
import com.msval.governance.engine.ops.MatchesPatternOp;
import com.msval.governance.engine.ops.NoneMatchIfOp;
import com.msval.governance.engine.ops.NotInSetOp;
import com.msval.governance.engine.ops.PromotionOrderOp;
import com.msval.governance.engine.ops.QuantityCompareOp;
import com.msval.governance.engine.ops.RangeOp;
import com.msval.governance.engine.ops.UniqueByOp;

/**
 * DD-009 — immutable operator registry. Java's capability set (F9 rev 5) is every operator
 * EXCEPT symbolic_capacity: {"runtime": ALL - {symbolic_capacity}} — the runtime engine
 * never solves; symbolic rules route to the Python engine at ci/intake.
 */
public final class OperatorRegistry {

    private final Map<String, Operator> operators;

    private OperatorRegistry(Map<String, Operator> operators) {
        this.operators = Collections.unmodifiableMap(new LinkedHashMap<>(operators));
    }

    /** All F9 standard operators plus promotion_order; deliberately no symbolic_capacity. */
    public static OperatorRegistry standard() {
        Map<String, Operator> ops = new LinkedHashMap<>();
        ops.put("exists", new ExistsOp());
        ops.put("equals", new EqualsOp());
        ops.put("matches_pattern", new MatchesPatternOp());
        ops.put("in_set", new InSetOp());
        ops.put("not_in_set", new NotInSetOp());
        ops.put("range", new RangeOp());
        ops.put("quantity_compare", new QuantityCompareOp());
        ops.put("all_match", new AllMatchOp());
        ops.put("none_match_if", new NoneMatchIfOp());
        ops.put("unique_by", new UniqueByOp());
        ops.put("graph_all_edges", new GraphAllEdgesOp());
        ops.put("graph_node_requires", new GraphNodeRequiresOp());
        ops.put("promotion_order", new PromotionOrderOp());
        return new OperatorRegistry(ops);
    }

    /** Null when unknown — the engine turns that into ENGINE_FAULT:UNKNOWN_OPERATOR. */
    public Operator get(String name) {
        return operators.get(name);
    }

    public Set<String> names() {
        return operators.keySet();
    }
}
