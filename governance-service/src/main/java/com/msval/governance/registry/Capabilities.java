package com.msval.governance.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Java mirror of msval/policy/capabilities.py (canonical stage ids, DD-010 rev 5):
 * ci = ALL operators, intake = {symbolic_capacity}, runtime = ALL − {symbolic_capacity}.
 * A stage evaluates the rules whose {@code phases} include its like-named phase.
 */
public final class Capabilities {

    public static final List<String> STAGES = List.of("ci", "intake", "runtime");

    /** The F9 operator catalog (14 operators — matches policy/conformance/ directories). */
    public static final Set<String> ALL = Set.of(
            "exists", "equals", "matches_pattern", "in_set", "not_in_set", "range",
            "quantity_compare", "all_match", "none_match_if", "unique_by",
            "graph_all_edges", "graph_node_requires", "promotion_order", "symbolic_capacity");

    private Capabilities() {
    }

    public static Set<String> operatorsFor(String stage) {
        return switch (stage) {
            case "ci" -> ALL;
            case "intake" -> Set.of("symbolic_capacity");
            case "runtime" -> minusSymbolic();
            default -> Set.of();
        };
    }

    private static Set<String> minusSymbolic() {
        return Set.copyOf(ALL.stream().filter(o -> !o.equals("symbolic_capacity")).toList());
    }

    /** Which stages will run a rule doc; empty = unroutable (IF-006 publish 422). */
    public static List<String> stagesFor(JsonNode rule) {
        String operator = rule.path("operator").asText();
        List<String> phases = new ArrayList<>();
        for (JsonNode p : rule.path("phases")) {
            phases.add(p.asText());
        }
        List<String> out = new ArrayList<>();
        for (String stage : STAGES) {
            if (operatorsFor(stage).contains(operator) && phases.contains(stage)) {
                out.add(stage);
            }
        }
        return out;
    }
}
