package com.msval.governance.findings;

/**
 * DD-015 — the single authority for the finding lifecycle. The matrix below is transcribed
 * cell-for-cell; every caller (applyEvaluation, the status/waive API, the waiver sweep)
 * resolves its move here and never encodes a transition anywhere else.
 *
 * <pre>
 * from \ trigger   | EVAL_FAIL          | EVAL_PASS   | ACK      | RESOLVE   | WAIVE    | SWEEP_EXPIRY
 * (none)           | OPEN (new row)     | —           | —        | —         | —        | —
 * OPEN             | OPEN (occ++)       | RESOLVED    | ACK'D    | RESOLVED  | WAIVED   | —
 * ACKNOWLEDGED     | ACK'D (occ++)      | RESOLVED    | 409      | RESOLVED  | WAIVED   | —
 * WAIVED           | WAIVED (occ++, no  | RESOLVED    | 409      | 409       | 409      | OPEN if last eval
 *                  |   alert)           |             |          |           |          |   fails else RESOLVED
 * RESOLVED         | new row OPEN       | —           | 409      | 409       | 409      | —
 * </pre>
 */
public final class Transitions {

    public enum Status { OPEN, ACKNOWLEDGED, WAIVED, RESOLVED }

    public enum Trigger { EVAL_FAIL, EVAL_PASS, ACK, RESOLVE, WAIVE, SWEEP_EXPIRY }

    public enum Kind {
        /** Move to {@link Outcome#target}. */
        MOVE,
        /** Stay in place, occurrences+1 ({@link Outcome#alert} says whether to emit). */
        STAY_INCREMENT,
        /** Insert a fresh OPEN row (from=none or from=RESOLVED on EVAL_FAIL). */
        NEW_ROW,
        /** Illegal transition — HTTP 409 (Part V CONFLICT). */
        CONFLICT,
        /** No effect. */
        NONE,
        /** WAIVED × SWEEP_EXPIRY: caller decides OPEN (last eval fails) vs RESOLVED. */
        EXPIRY_CHECK
    }

    public record Outcome(Kind kind, Status target, boolean alert) {
        static Outcome move(Status target) {
            return new Outcome(Kind.MOVE, target, true);
        }

        static Outcome stay(boolean alert) {
            return new Outcome(Kind.STAY_INCREMENT, null, alert);
        }

        static final Outcome NEW_ROW = new Outcome(Kind.NEW_ROW, Status.OPEN, true);
        static final Outcome CONFLICT = new Outcome(Kind.CONFLICT, null, false);
        static final Outcome NONE = new Outcome(Kind.NONE, null, false);
        static final Outcome EXPIRY = new Outcome(Kind.EXPIRY_CHECK, null, true);
    }

    private Transitions() {
    }

    /** {@code from == null} means no unresolved row exists for the dedup key. */
    public static Outcome next(Status from, Trigger trigger) {
        if (from == null) {
            return trigger == Trigger.EVAL_FAIL ? Outcome.NEW_ROW : Outcome.NONE;
        }
        return switch (from) {
            case OPEN -> switch (trigger) {
                case EVAL_FAIL -> Outcome.stay(true);
                case EVAL_PASS -> Outcome.move(Status.RESOLVED);
                case ACK -> Outcome.move(Status.ACKNOWLEDGED);
                case RESOLVE -> Outcome.move(Status.RESOLVED);
                case WAIVE -> Outcome.move(Status.WAIVED);
                case SWEEP_EXPIRY -> Outcome.NONE;
            };
            case ACKNOWLEDGED -> switch (trigger) {
                case EVAL_FAIL -> Outcome.stay(true);
                case EVAL_PASS -> Outcome.move(Status.RESOLVED);
                case ACK -> Outcome.CONFLICT;
                case RESOLVE -> Outcome.move(Status.RESOLVED);
                case WAIVE -> Outcome.move(Status.WAIVED);
                case SWEEP_EXPIRY -> Outcome.NONE;
            };
            case WAIVED -> switch (trigger) {
                case EVAL_FAIL -> Outcome.stay(false);       // occ++, no alert
                case EVAL_PASS -> Outcome.move(Status.RESOLVED);
                case ACK, RESOLVE, WAIVE -> Outcome.CONFLICT;
                case SWEEP_EXPIRY -> Outcome.EXPIRY;
            };
            case RESOLVED -> switch (trigger) {
                case EVAL_FAIL -> Outcome.NEW_ROW;
                case EVAL_PASS -> Outcome.NONE;
                case ACK, RESOLVE, WAIVE -> Outcome.CONFLICT;
                case SWEEP_EXPIRY -> Outcome.NONE;
            };
        };
    }
}
