package com.msval.governance.registry;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** One stage's active rules, parsed once at cache swap (DD-012). */
public record RuleSet(String version, List<JsonNode> rules) {

    public static final RuleSet EMPTY = new RuleSet(null, List.of());
}
