package com.msval.governance.findings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.msval.governance.findings.Transitions.Kind;
import com.msval.governance.findings.Transitions.Outcome;
import com.msval.governance.findings.Transitions.Status;
import com.msval.governance.findings.Transitions.Trigger;

/**
 * TEST-007 — the DD-015 matrix, every (from × trigger) cell including the 409 paths.
 * 5 from-states (incl. none) × 6 triggers = 30 cells, asserted cell-for-cell.
 */
class TransitionsTest {

    @ParameterizedTest(name = "from={0} trigger={1} -> {2}")
    @CsvSource({
            // from=(none): only a failing evaluation creates a row
            ",EVAL_FAIL,NEW_ROW,OPEN",
            ",EVAL_PASS,NONE,",
            ",ACK,NONE,",
            ",RESOLVE,NONE,",
            ",WAIVE,NONE,",
            ",SWEEP_EXPIRY,NONE,",
            // OPEN
            "OPEN,EVAL_FAIL,STAY_INCREMENT,",
            "OPEN,EVAL_PASS,MOVE,RESOLVED",
            "OPEN,ACK,MOVE,ACKNOWLEDGED",
            "OPEN,RESOLVE,MOVE,RESOLVED",
            "OPEN,WAIVE,MOVE,WAIVED",
            "OPEN,SWEEP_EXPIRY,NONE,",
            // ACKNOWLEDGED
            "ACKNOWLEDGED,EVAL_FAIL,STAY_INCREMENT,",
            "ACKNOWLEDGED,EVAL_PASS,MOVE,RESOLVED",
            "ACKNOWLEDGED,ACK,CONFLICT,",
            "ACKNOWLEDGED,RESOLVE,MOVE,RESOLVED",
            "ACKNOWLEDGED,WAIVE,MOVE,WAIVED",
            "ACKNOWLEDGED,SWEEP_EXPIRY,NONE,",
            // WAIVED
            "WAIVED,EVAL_FAIL,STAY_INCREMENT,",
            "WAIVED,EVAL_PASS,MOVE,RESOLVED",
            "WAIVED,ACK,CONFLICT,",
            "WAIVED,RESOLVE,CONFLICT,",
            "WAIVED,WAIVE,CONFLICT,",
            "WAIVED,SWEEP_EXPIRY,EXPIRY_CHECK,",
            // RESOLVED
            "RESOLVED,EVAL_FAIL,NEW_ROW,OPEN",
            "RESOLVED,EVAL_PASS,NONE,",
            "RESOLVED,ACK,CONFLICT,",
            "RESOLVED,RESOLVE,CONFLICT,",
            "RESOLVED,WAIVE,CONFLICT,",
            "RESOLVED,SWEEP_EXPIRY,NONE,",
    })
    void matrixCell(String from, String trigger, String kind, String target) {
        Status f = from == null || from.isEmpty() ? null : Status.valueOf(from);
        Outcome o = Transitions.next(f, Trigger.valueOf(trigger));
        assertEquals(Kind.valueOf(kind), o.kind());
        if (target != null && !target.isEmpty()) {
            assertEquals(Status.valueOf(target), o.target());
        }
    }

    @Test
    void openAndAcknowledgedIncrementsAlert_waivedIncrementIsSilent() {
        assertTrue(Transitions.next(Status.OPEN, Trigger.EVAL_FAIL).alert());
        assertTrue(Transitions.next(Status.ACKNOWLEDGED, Trigger.EVAL_FAIL).alert());
        assertFalse(Transitions.next(Status.WAIVED, Trigger.EVAL_FAIL).alert(),
                "matrix: WAIVED occ++ emits no alert");
    }
}
