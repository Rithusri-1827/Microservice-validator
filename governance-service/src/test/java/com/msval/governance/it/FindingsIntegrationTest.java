package com.msval.governance.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.msval.governance.engine.CheckResult;
import com.msval.governance.engine.Decision;
import com.msval.governance.findings.FindingsDelta;
import com.msval.governance.findings.FindingsService;
import com.msval.governance.persist.EvaluationRepo;
import com.msval.governance.persist.ServiceKey;
import com.msval.governance.persist.ViolationRepo;
import com.msval.governance.persist.WaiverRepo;
import com.msval.governance.support.HttpError;

/**
 * TEST-007/008 (SQL half) — applyEvaluation upsert/occurrence/auto-resolve against the
 * real v_open partial index, the 409 operator paths, and the waiver expiry sweep branches.
 */
class FindingsIntegrationTest {

    private final JdbcTemplate jdbc = TestDb.jdbc();
    private final ViolationRepo violations = new ViolationRepo(jdbc);
    private final WaiverRepo waivers = new WaiverRepo(jdbc);
    private final EvaluationRepo evaluations = new EvaluationRepo(jdbc);
    private final FindingsService findings =
            new FindingsService(violations, waivers, evaluations, TestDb.txn());
    private final ServiceKey key = new ServiceKey("svc-f", "1.0.0", "dev");

    @BeforeEach
    void clean() {
        TestDb.truncate();
    }

    private static Decision failing(String... ruleIds) {
        List<CheckResult> blocking = List.of(ruleIds).stream()
                .map(id -> new CheckResult(id, "containers[0]", false, "RULE_FAILED", "failed"))
                .toList();
        return new Decision("FAIL", blocking, List.of(), List.of(),
                List.of(ruleIds).stream().sorted().toList(), "vtest", "1.0", 1);
    }

    private static Decision passing(String... evaluated) {
        return new Decision("PASS", List.of(), List.of(), List.of(),
                List.of(evaluated).stream().sorted().toList(), "vtest", "1.0", 1);
    }

    @Test
    void failOpensThenDedupsThenAutoResolves() {
        FindingsDelta d1 = findings.applyEvaluation(failing("SEC-002"), key, "evaluation");
        assertEquals(1, d1.opened.size());
        assertEquals("OPEN", d1.opened.get(0).status());

        // TEST-007 concurrency shape: same key fails again ⇒ single open row, occurrences=2
        FindingsDelta d2 = findings.applyEvaluation(failing("SEC-002"), key, "evaluation");
        assertEquals(0, d2.opened.size());
        assertEquals(1, d2.updated.size());
        List<ViolationRepo.ViolationRow> rows =
                violations.board("svc-f", "dev", null, "SEC-002", 0, 10);
        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).occurrences());
        assertEquals("OPEN", rows.get(0).status());

        // rule passes ⇒ auto-resolve (uses evaluated_rules)
        FindingsDelta d3 = findings.applyEvaluation(passing("SEC-002"), key, "evaluation");
        assertEquals(1, d3.autoResolved.size());
        assertEquals("RESOLVED", violations.byId(d3.autoResolved.get(0).id()).orElseThrow().status());

        // RESOLVED × EVAL_FAIL ⇒ brand-new row (matrix)
        FindingsDelta d4 = findings.applyEvaluation(failing("SEC-002"), key, "evaluation");
        assertEquals(1, d4.opened.size());
        assertEquals(2, violations.boardTotal("svc-f", "dev", null, "SEC-002"));
    }

    @Test
    void unevaluatedRuleIsNotAutoResolved() {
        findings.applyEvaluation(failing("SEC-002"), key, "evaluation");
        // Next evaluation does not include SEC-002 in evaluated_rules (rule left the bundle)
        findings.applyEvaluation(passing("SPR-001"), key, "evaluation");
        assertEquals("OPEN", violations.board("svc-f", "dev", null, "SEC-002", 0, 1).get(0).status());
    }

    @Test
    void operatorTransitionsAndConflicts() {
        FindingsDelta d = findings.applyEvaluation(failing("SEC-002"), key, "evaluation");
        long id = d.opened.get(0).id();

        assertEquals("ACKNOWLEDGED", findings.setStatus(id, "ACKNOWLEDGED").status());
        HttpError dup = assertThrows(HttpError.class, () -> findings.setStatus(id, "ACKNOWLEDGED"));
        assertEquals(409, dup.status());

        assertEquals("RESOLVED", findings.setStatus(id, "RESOLVED").status());
        assertEquals(409, assertThrows(HttpError.class,
                () -> findings.setStatus(id, "RESOLVED")).status());
        assertEquals(409, assertThrows(HttpError.class,
                () -> findings.waive(id, Instant.now().plusSeconds(3600), "ops", "r")).status());
        assertEquals(404, assertThrows(HttpError.class,
                () -> findings.setStatus(99_999, "RESOLVED")).status());
        assertEquals(422, assertThrows(HttpError.class,
                () -> findings.setStatus(id, "SIDEWAYS")).status());
    }

    @Test
    void waiveMovesToWaivedAndSilencesIncrements() {
        long id = findings.applyEvaluation(failing("SEC-002"), key, "evaluation").opened.get(0).id();
        long waiverId = findings.waive(id, Instant.now().plusSeconds(3600), "alice", "known issue");
        assertTrue(waiverId > 0);
        ViolationRepo.ViolationRow row = violations.byId(id).orElseThrow();
        assertEquals("WAIVED", row.status());
        assertEquals(waiverId, row.waiverId());

        // WAIVED × EVAL_FAIL ⇒ occ++, silent (matrix)
        FindingsDelta d = findings.applyEvaluation(failing("SEC-002"), key, "evaluation");
        assertEquals(1, d.updated.size());
        assertFalse(d.updated.get(0).alert());
        assertEquals(2, violations.byId(id).orElseThrow().occurrences());
        assertEquals("WAIVED", violations.byId(id).orElseThrow().status());

        // engine sees the waiver via activeWaivers
        assertEquals("SEC-002", findings.activeWaivers(key).get(0).ruleId());

        // WAIVED × EVAL_PASS ⇒ RESOLVED
        findings.applyEvaluation(passing("SEC-002"), key, "evaluation");
        assertEquals("RESOLVED", violations.byId(id).orElseThrow().status());
    }

    @Test
    void expirySweepReopensWhenStillFailing() {
        long id = findings.applyEvaluation(failing("SEC-002"), key, "evaluation").opened.get(0).id();
        long wid = waivers.insert("SEC-002", key, "alice", "r", Instant.now().minusSeconds(5));
        violations.updateStatus(id, "WAIVED", wid);

        List<FindingsService.ExpiredWaiver> expired = findings.sweepExpiredWaivers();
        assertEquals(1, expired.size());
        assertEquals("OPEN", expired.get(0).outcome());
        assertEquals("OPEN", violations.byId(id).orElseThrow().status());
        assertEquals("expired", jdbc.queryForObject(
                "SELECT status FROM waivers WHERE id = ?", String.class, wid));
    }

    @Test
    void expirySweepResolvesWhenLastEvalPassed() throws InterruptedException {
        long id = findings.applyEvaluation(failing("SEC-002"), key, "evaluation").opened.get(0).id();
        long wid = waivers.insert("SEC-002", key, "alice", "r", Instant.now().minusSeconds(5));
        violations.updateStatus(id, "WAIVED", wid);
        Thread.sleep(50); // evaluation strictly after the finding's last_seen
        evaluations.insert("eval-pass-1", key, "runtime", "vtest", "PASS", 5);

        List<FindingsService.ExpiredWaiver> expired = findings.sweepExpiredWaivers();
        assertEquals("RESOLVED", expired.get(0).outcome());
        assertEquals("RESOLVED", violations.byId(id).orElseThrow().status());
    }

    @Test
    void engineWaivedResultInsertsSilentWaivedRow() {
        CheckResult r = new CheckResult("SEC-002", "containers[0]", false, "RULE_FAILED", "failed");
        Decision d = new Decision("PASS", List.of(), List.of(),
                List.of(new Decision.Waived(r, 42L)), List.of("SEC-002"), "vtest", "1.0", 1);
        FindingsDelta delta = findings.applyEvaluation(d, key, "evaluation");
        assertEquals(1, delta.opened.size());
        assertFalse(delta.opened.get(0).alert(), "fresh WAIVED row is silent");
        assertEquals("WAIVED", delta.opened.get(0).status());
        ViolationRepo.ViolationRow row = violations.byId(delta.opened.get(0).id()).orElseThrow();
        assertEquals(42L, row.waiverId());
    }

    @Test
    void openCountsAndBlockingIdsViews() {
        findings.applyEvaluation(failing("SEC-002", "SEC-003"), key, "evaluation");
        var counts = violations.openCounts();
        assertEquals(2, counts.get("dev").get("OPEN"));
        assertEquals(List.of("SEC-002", "SEC-003"), violations.openBlockingRuleIds(key));
    }
}
