package com.msval.governance.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Socket;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.engine.Engine;
import com.msval.governance.engine.OperatorRegistry;
import com.msval.governance.findings.FindingsService;
import com.msval.governance.gateway.AlertBroadcaster;
import com.msval.governance.gateway.FrameCodec;
import com.msval.governance.gateway.GatewayServer;
import com.msval.governance.orchestrate.ContextAssembler;
import com.msval.governance.orchestrate.EvaluationCommitter;
import com.msval.governance.orchestrate.IntakeService;
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
import com.msval.governance.registry.RegistryService;
import com.msval.governance.registry.RuleCache;

/**
 * TEST-010/011 (SQL half) — Txn A registration, settle claim → validate → findings,
 * status-report drift fast path, supersede, decommission, and the W3 gateway leg
 * (ACK + dedup) against the real store.
 */
class OrchestratorIntegrationTest {

    private final JdbcTemplate jdbc = TestDb.jdbc();
    private final TransactionTemplate txn = TestDb.txn();
    private final ViolationRepo violations = new ViolationRepo(jdbc);
    private final LifecycleRepo lifecycle = new LifecycleRepo(jdbc);
    private final ServiceRepo services = new ServiceRepo(jdbc);
    private final TopologyRepo topology = new TopologyRepo(jdbc);
    private final EvaluationRepo evaluations = new EvaluationRepo(jdbc);
    private final BundleRepo bundles = new BundleRepo(jdbc);
    private final RuleCache cache = new RuleCache(bundles);
    private final RegistryService registry =
            new RegistryService(bundles, new AuditRepo(jdbc), cache, txn);
    private final FindingsService findings = new FindingsService(violations,
            new WaiverRepo(jdbc), evaluations, txn);
    private final AlertBroadcaster alerts =
            new AlertBroadcaster(TestDb.props(), cache, violations);
    private final EvaluationCommitter committer = new EvaluationCommitter(
            new ContextAssembler(new EnvironmentRepo(jdbc), services, lifecycle, topology, findings),
            new Engine(OperatorRegistry.standard()), cache, lifecycle, evaluations, findings,
            alerts, txn);
    private final IntakeService intake = new IntakeService(TestDb.props(), services, lifecycle,
            evaluations, new EnvironmentRepo(jdbc), topology, committer, alerts, txn, findings);

    private GatewayServer gateway;

    @BeforeEach
    void seed() throws Exception {
        TestDb.truncate();
        registry.publish(Jsonb.MAPPER.readTree("""
                {"manifest":{"version":"vtest01","grammar_version":"1"},
                 "stage_sets":{"ci":[],"intake":[],"runtime":[
                   {"id":"SEC-002","family":"security","title":"pinned images",
                    "target":"workload.containers[].image.pinned","operator":"equals",
                    "params":{"value":true},"severity":"BLOCK","phases":["ci","runtime"],
                    "environments":["*"],"message":"not pinned"}]}}
                """));
        registry.activate("vtest01");
    }

    @AfterEach
    void teardown() {
        if (gateway != null) {
            gateway.stop();
            gateway = null;
        }
        intake.stop();
    }

    private static ObjectNode deploymentFrame(String eventId, String sid, String ver, String env,
            boolean pinned) throws Exception {
        JsonNode cdm = Jsonb.MAPPER.readTree("""
                {"cdm_version":"1.0","service":{"id":"%s","name":"%s","layer":"Domain"},
                 "version":{"tag":"%s"},"environment":"%s",
                 "workload":{"replicas":1,"containers":[{"name":"app",
                   "image":{"repository":"payments/api","registry":"harbor.internal",
                            "ref":"api:%s","pinned":%s}}]}}
                """.formatted(sid, sid, ver, env, ver, pinned));
        ObjectNode envelope = Jsonb.MAPPER.createObjectNode();
        envelope.put("event_id", eventId);
        envelope.put("kind", "deployment");
        envelope.put("service_id", sid);
        envelope.put("service_version", ver);
        envelope.put("environment", env);
        envelope.put("timestamp", "2026-08-14T10:00:00Z");
        envelope.set("approved_cdm", cdm);
        ObjectNode frame = Jsonb.MAPPER.createObjectNode();
        frame.set("envelope", envelope);
        frame.set("cdm", cdm);
        frame.putArray("intake_checks");
        frame.put("ingress_bundle_version", "vtest01");
        frame.put("forwarded_at", "2026-08-14T10:00:00Z");
        return frame;
    }

    @Test
    void deployThenSettleThenDriftReport() throws Exception {
        ServiceKey key = new ServiceKey("svc-x", "1.0", "dev");
        intake.dispatch(deploymentFrame("e-dep-1", "svc-x", "1.0", "dev", false));

        // Txn A effects: Deployed + persisted settle timer + intake receipt + baseline
        LifecycleRepo.LifecycleRow row = lifecycle.find(key).orElseThrow();
        assertEquals("Deployed", row.state());
        assertNotNull(row.nextValidationAt(), "settle timer persisted (restart-safe)");
        assertTrue(services.declaredCdm("svc-x", "1.0").isPresent());
        assertEquals("PASS", jdbc.queryForObject(
                "SELECT verdict FROM evaluations WHERE event_id = 'e-dep-1'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lifecycle_history "
                + "WHERE service_id = 'svc-x' AND to_state = 'Deployed'", Integer.class));

        // settle: claim due rows (SKIP LOCKED semantics) → validate
        jdbc.update("UPDATE lifecycle_states SET next_validation_at = now() - interval '1 second' "
                + "WHERE service_id = 'svc-x'");
        List<ServiceKey> due = txn.execute(s -> lifecycle.claimDue(50));
        assertEquals(List.of(key), due);
        assertNull(lifecycle.find(key).orElseThrow().nextValidationAt(), "claim clears the timer");
        committer.validate(key);

        assertEquals("ValidationFailed", lifecycle.find(key).orElseThrow().state());
        var open = violations.board("svc-x", "dev", "OPEN", "SEC-002", 0, 10);
        assertEquals(1, open.size(), "unpinned image violates SEC-002");
        assertEquals("FAIL", evaluations.latestRuntime(key).orElseThrow().verdict());

        // status report: live image now pinned but ref changed ⇒ SEC-002 passes, CONFIG_DRIFT
        ObjectNode report = Jsonb.MAPPER.createObjectNode();
        report.put("event_id", "e-rep-1");
        report.put("kind", "status_report");
        report.put("service_id", "svc-x");
        report.put("service_version", "1.0");
        report.put("environment", "dev");
        report.put("timestamp", "2026-08-14T10:05:00Z");
        report.put("trigger", "change");
        report.set("live_state", Jsonb.MAPPER.readTree("""
                {"containers":[{"name":"app","image":{"repository":"payments/api",
                  "registry":"harbor.internal","ref":"api:1.0-hotfix","pinned":true}}]}
                """));
        intake.dispatch(report);

        assertEquals("Validated", lifecycle.find(key).orElseThrow().state(),
                "merged doc passes; drift is WARN outside production");
        assertEquals("RESOLVED", violations.board("svc-x", "dev", null, "SEC-002", 0, 10)
                .get(0).status(), "auto-resolved via evaluated_rules");
        var drift = violations.board("svc-x", "dev", "OPEN", "DRIFT-CONFIG", 0, 10);
        assertEquals(1, drift.size(), "declared-vs-live diff surfaces CONFIG_DRIFT");
        assertEquals("WARN", drift.get(0).detail().path("severity").asText());
        assertTrue(drift.get(0).detail().path("detail").asText().contains("image"));
        assertNotNull(lifecycle.find(key).orElseThrow().lastReportAt());

        // fix the drift: live matches declared again ⇒ DRIFT-CONFIG auto-resolves
        report.put("event_id", "e-rep-2");
        report.set("live_state", Jsonb.MAPPER.readTree("""
                {"containers":[{"name":"app","image":{"repository":"payments/api",
                  "registry":"harbor.internal","ref":"api:1.0","pinned":false}}]}
                """));
        intake.dispatch(report);
        assertEquals("RESOLVED", violations.board("svc-x", "dev", null, "DRIFT-CONFIG", 0, 10)
                .get(0).status());
        assertEquals("ValidationFailed", lifecycle.find(key).orElseThrow().state(),
                "live pinned=false again fails SEC-002");
    }

    @Test
    void newVersionSupersedesSiblings() throws Exception {
        intake.dispatch(deploymentFrame("e-s1", "svc-y", "1.0", "dev", true));
        intake.dispatch(deploymentFrame("e-s2", "svc-y", "1.1", "dev", true));
        assertEquals("Superseded",
                lifecycle.find(new ServiceKey("svc-y", "1.0", "dev")).orElseThrow().state());
        assertEquals("Deployed",
                lifecycle.find(new ServiceKey("svc-y", "1.1", "dev")).orElseThrow().state());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lifecycle_history "
                + "WHERE service_id = 'svc-y' AND to_state = 'Superseded'", Integer.class));
    }

    @Test
    void decommissionRetiresTheVersion() throws Exception {
        intake.dispatch(deploymentFrame("e-d1", "svc-z", "1.0", "dev", true));
        ObjectNode frame = deploymentFrame("e-d2", "svc-z", "1.0", "dev", true);
        ((ObjectNode) frame.get("envelope")).put("decommission", true);
        intake.dispatch(frame);
        LifecycleRepo.LifecycleRow row =
                lifecycle.find(new ServiceKey("svc-z", "1.0", "dev")).orElseThrow();
        assertEquals("Decommissioned", row.state());
        assertNull(row.nextValidationAt(), "no settle validation for decommissioned versions");
    }

    @Test
    void topologyDeltaAppendsDeclaredSnapshot() throws Exception {
        ObjectNode frame = deploymentFrame("e-t1", "svc-t", "1.0", "dev", true);
        ((ObjectNode) frame.get("envelope")).set("topology_delta", Jsonb.MAPPER.readTree(
                "{\"connections_add\":[{\"to\":\"svc-db\",\"encrypted\":true}],\"entry_point\":true}"));
        intake.dispatch(frame);
        JsonNode graph = topology.latest("dev", "declared").orElseThrow();
        assertTrue(graph.path("nodes").toString().contains("svc-t"));
        assertEquals("svc-t", graph.path("edges").get(0).path("from").asText());
        assertEquals("svc-db", graph.path("edges").get(0).path("to").asText());
        assertTrue(graph.path("node_attrs").path("svc-t").path("entry_point").asBoolean());
    }

    @Test
    void gatewayAcksAndDedupsOverTheWire() throws Exception {
        intake.start();
        gateway = new GatewayServer(TestDb.props(), intake);
        gateway.start();

        String eventId = UUID.randomUUID().toString();
        try (Socket socket = new Socket("127.0.0.1", TestDb.props().gatewayPort())) {
            ObjectNode frame = deploymentFrame(eventId, "svc-gw", "3.0", "dev", true);
            FrameCodec.write(socket.getOutputStream(), frame);
            JsonNode ack1 = FrameCodec.read(socket.getInputStream());
            assertEquals(eventId, ack1.get("event_id").asText());
            assertTrue(ack1.get("ok").asBoolean());

            FrameCodec.write(socket.getOutputStream(), frame); // duplicate within window
            JsonNode ack2 = FrameCodec.read(socket.getInputStream());
            assertTrue(ack2.get("ok").asBoolean(), "duplicate is ACK-only");

            // garbage payload ⇒ NACK, connection survives
            socket.getOutputStream().write(new byte[] {0, 0, 0, 2, '!', '!'});
            socket.getOutputStream().flush();
            JsonNode nack = FrameCodec.read(socket.getInputStream());
            assertEquals(false, nack.get("ok").asBoolean());

            FrameCodec.write(socket.getOutputStream(),
                    deploymentFrame(UUID.randomUUID().toString(), "svc-gw", "3.1", "dev", true));
            assertTrue(FrameCodec.read(socket.getInputStream()).get("ok").asBoolean(),
                    "connection still usable after NACK");
        }

        // exactly one intake receipt despite the duplicate (TEST-006)
        long deadline = System.currentTimeMillis() + 10_000;
        Integer count = 0;
        while (System.currentTimeMillis() < deadline) {
            count = jdbc.queryForObject("SELECT count(*) FROM evaluations WHERE event_id = ?",
                    Integer.class, eventId);
            if (count != null && count > 0
                    && lifecycle.find(new ServiceKey("svc-gw", "3.1", "dev")).isPresent()) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals(1, count, "single evaluation row for the deduped event");
    }
}
