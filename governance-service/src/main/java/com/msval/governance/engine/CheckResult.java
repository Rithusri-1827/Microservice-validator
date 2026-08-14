package com.msval.governance.engine;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * F5 — one rule evaluation at one concrete path (IF-001).
 * {@code observed} is the offending value when failed (null when passed), truncated at 2 KB.
 */
public record CheckResult(
        String ruleId,
        String path,
        boolean passed,
        String reasonCode,
        String detail,
        JsonNode observed) {

    public CheckResult(String ruleId, String path, boolean passed, String reasonCode, String detail) {
        this(ruleId, path, passed, reasonCode, detail, null);
    }
}
