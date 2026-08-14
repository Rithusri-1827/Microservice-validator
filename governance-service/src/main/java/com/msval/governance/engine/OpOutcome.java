package com.msval.governance.engine;

/** The (passed, reason_code, detail) triple returned by an operator — mirror of the Python tuple. */
public record OpOutcome(boolean passed, String reasonCode, String detail) {

    public static OpOutcome pass() {
        return new OpOutcome(true, "OK", "");
    }

    public static OpOutcome fail(String detail) {
        return new OpOutcome(false, "RULE_FAILED", detail);
    }

    public static OpOutcome fail(String reasonCode, String detail) {
        return new OpOutcome(false, reasonCode, detail);
    }
}
