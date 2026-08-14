package com.msval.governance.engine;

import java.util.List;

/**
 * F5 — the engine's verdict for one document under one rule set (IF-001).
 * Lists are sorted by (ruleId, path); {@code durationMs} is excluded from the parity diff.
 */
public record Decision(
        String verdict,               // PASS | FAIL | ERROR
        List<CheckResult> blocking,
        List<CheckResult> warnings,
        List<Waived> waived,
        List<String> evaluatedRules,
        String evaluatedUnder,
        String cdmVersion,
        long durationMs) {

    /** A failed CheckResult suppressed by an active waiver. */
    public record Waived(CheckResult result, long waiverId) {
    }
}
