package com.msval.governance.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.msval.governance.engine.Engine;
import com.msval.governance.engine.OperatorRegistry;
import com.msval.governance.findings.FindingsService;
import com.msval.governance.gateway.AlertBroadcaster;
import com.msval.governance.orchestrate.ContextAssembler;
import com.msval.governance.orchestrate.EvaluationCommitter;
import com.msval.governance.orchestrate.Reconciler;
import com.msval.governance.persist.AuditRepo;
import com.msval.governance.persist.BundleRepo;
import com.msval.governance.persist.EnvironmentRepo;
import com.msval.governance.persist.EvaluationRepo;
import com.msval.governance.persist.Jsonb;
import com.msval.governance.persist.LifecycleRepo;
import com.msval.governance.persist.ServiceKey;
import com.msval.governance.persist.ServiceRepo;
import com.msval.governance.persist.TopologyRepo;
import com.msval.governance.persist.ViolationRepo;
import com.msval.governance.persist.WaiverRepo;
import com.msval.governance.registry.RuleCache;

/**
 * TEST-012 (inventory lane) — seed each DD-013 anomaly, run the four reconciliation
 * queries against real Postgres, assert exactly the four RECON-* finding kinds, then
 * clear the anomalies and assert auto-resolution.
 */
class ReconcilerIntegrationTest {

    private final JdbcTemplate jdbc = TestDb.jdbc();
    private final TransactionTemplate txn = TestDb.txn();
    private final ViolationRepo violations = new ViolationRepo(jdbc);
    private final LifecycleRepo lifecycle = new LifecycleRepo(jdbc);
    private final ServiceRepo services = new ServiceRepo(jdbc);
    private final TopologyRepo topology = new TopologyRepo(jdbc);
    private final FindingsService findings = new FindingsService(violations,
            new WaiverRepo(jdbc), new EvaluationRepo(jdbc), txn);
    private final Reconciler reconciler = buildReconciler();

    private Reconciler buildReconciler() {
        RuleCache cache = new RuleCache(new BundleRepo(jdbc));
        AlertBroadcaster alerts = new AlertBroadcaster(TestDb.props(), cache, violations);
        ContextAssembler assembler = new ContextAssembler(new EnvironmentRepo(jdbc), services,
                lifecycle, topology, findings);
        EvaluationCommitter committer = new EvaluationCommitter(assembler,
                new Engine(OperatorRegistry.standard()), cache, lifecycle,
                new EvaluationRepo(jdbc), findings, alerts, txn);
        return new Reconciler(jdbc, TestDb.props(), findings, lifecycle, committer, txn);
    }

    @BeforeEach
    void seedAnomalies() throws Exception {
        TestDb.truncate();
        // registered services
        services.upsertService("svc-a", "svc-a", "Domain", null);
        services.upsertVersion("svc-a", "1.0", null, null);
        services.upsertVersion("svc-a", "0.9", null, null);
        services.upsertService("svc-c", "svc-c", "Domain", null);
        services.upsertVersion("svc-c", "2.0", null, null);
        // svc-a:1.0 live in dev but silent too long (report_interval 300 × stale_factor 3 = 900 s)
        lifecycle.upsertDeployed(new ServiceKey("svc-a", "1.0", "dev"), null);
        lifecycle.setState(new ServiceKey("svc-a", "1.0", "dev"), "Validated", null);
        jdbc.update("UPDATE lifecycle_states SET last_report_at = now() - interval '2 hours' "
                + "WHERE service_id = 'svc-a' AND version = '1.0'");
        // svc-a:0.9 superseded yet still reporting ⇒ skew
        lifecycle.upsertDeployed(new ServiceKey("svc-a", "0.9", "dev"), null);
        lifecycle.setState(new ServiceKey("svc-a", "0.9", "dev"), "Superseded", null);
        jdbc.update("UPDATE lifecycle_states SET last_report_at = now() "
                + "WHERE service_id = 'svc-a' AND version = '0.9'");
        // svc-c:2.0 reports (captured) but is not declared ⇒ undeclared
        lifecycle.upsertDeployed(new ServiceKey("svc-c", "2.0", "dev"), null);
        jdbc.update("UPDATE lifecycle_states SET last_report_at = now() "
                + "WHERE service_id = 'svc-c'");
        // declared topology: svc-a + svc-b (svc-b never deployed ⇒ missing)
        topology.append("dev", "declared", Jsonb.MAPPER.readTree(
                "{\"nodes\":[\"svc-a\",\"svc-b\"],\"edges\":[]}"));
        // captured topology: svc-a + svc-c
        topology.append("dev", "captured", Jsonb.MAPPER.readTree(
                "{\"nodes\":[\"svc-a\",\"svc-c\"],\"edges\":[]}"));
    }

    @Test
    void allFourAnomalyKindsAreFoundThenAutoResolved() throws Exception {
        reconciler.run("dev");

        assertEquals("svc-b", one("RECON-MISSING").serviceId());
        assertEquals("-", one("RECON-MISSING").version());
        assertEquals("svc-c", one("RECON-UNDECLARED").serviceId());
        assertEquals("2.0", one("RECON-UNDECLARED").version(),
                "IF-014: version component is the reported version verbatim");
        assertEquals("svc-a", one("RECON-STALE").serviceId());
        assertEquals("1.0", one("RECON-STALE").version());
        assertEquals("svc-a", one("RECON-SKEW").serviceId());
        assertEquals("0.9", one("RECON-SKEW").version());
        assertTrue(jdbc.queryForObject("SELECT stale FROM lifecycle_states "
                + "WHERE service_id = 'svc-a' AND version = '1.0'", Boolean.class),
                "STALE additionally sets the stale flag (DD-013)");

        // second run without changes: dedup, not duplicates
        reconciler.run("dev");
        assertEquals(1, violations.boardTotal(null, "dev", null, "RECON-MISSING"));
        assertEquals(2, one("RECON-MISSING").occurrences());

        // clear every anomaly …
        services.upsertService("svc-b", "svc-b", "Domain", null);
        services.upsertVersion("svc-b", "1.0", null, null);
        lifecycle.upsertDeployed(new ServiceKey("svc-b", "1.0", "dev"), null);
        topology.append("dev", "declared", Jsonb.MAPPER.readTree(
                "{\"nodes\":[\"svc-a\",\"svc-b\",\"svc-c\"],\"edges\":[]}"));
        jdbc.update("UPDATE lifecycle_states SET last_report_at = now() "
                + "WHERE service_id = 'svc-a' AND version = '1.0'");
        jdbc.update("UPDATE lifecycle_states SET last_report_at = now() - interval '1 hour' "
                + "WHERE service_id = 'svc-a' AND version = '0.9'");

        // … and the same uniform lifecycle resolves them
        reconciler.run("dev");
        for (String rule : List.of("RECON-MISSING", "RECON-UNDECLARED", "RECON-STALE", "RECON-SKEW")) {
            assertEquals("RESOLVED", one(rule).status(), rule + " should auto-resolve");
        }
    }

    @Test
    void missingDeploymentQueryReturnsEveryNodeOfTheNewestSnapshot() throws Exception {
        // regression for the LIMIT-vs-SRF correction: two absent nodes must both surface
        topology.append("dev", "declared", Jsonb.MAPPER.readTree(
                "{\"nodes\":[\"svc-a\",\"ghost-1\",\"ghost-2\"],\"edges\":[]}"));
        reconciler.run("dev");
        assertEquals(2, violations.boardTotal(null, "dev", null, "RECON-MISSING")
                - violations.board(null, "dev", "RESOLVED", "RECON-MISSING", 0, 50).size(),
                "both ghost nodes flagged");
    }

    private ViolationRepo.ViolationRow one(String ruleId) {
        List<ViolationRepo.ViolationRow> rows = violations.board(null, "dev", null, ruleId, 0, 10);
        assertEquals(1, rows.size(), ruleId + ": expected exactly one finding, got " + rows);
        return rows.get(0);
    }
}
