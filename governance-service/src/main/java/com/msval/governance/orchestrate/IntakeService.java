package com.msval.governance.orchestrate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.config.MsvalProperties;
import com.msval.governance.gateway.AlertBroadcaster;
import com.msval.governance.persist.EnvironmentRepo;
import com.msval.governance.persist.EvaluationRepo;
import com.msval.governance.persist.Jsonb;
import com.msval.governance.persist.LifecycleRepo;
import com.msval.governance.persist.ServiceKey;
import com.msval.governance.persist.ServiceRepo;
import com.msval.governance.persist.TopologyRepo;

/**
 * DD-013 — IntakeService: ArrayBlockingQueue(cfg.queueBound) fed by the gateway
 * (blocking put = ADR-006 backpressure), 2×cores workers, dispatch by kind.
 * Txn A (registerDeployment) follows the normative SQL order verbatim.
 */
@Service
public class IntakeService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final ArrayBlockingQueue<JsonNode> queue;
    private final MsvalProperties cfg;
    private final ServiceRepo services;
    private final LifecycleRepo lifecycle;
    private final EvaluationRepo evaluations;
    private final EnvironmentRepo environments;
    private final TopologyRepo topology;
    private final EvaluationCommitter committer;
    private final AlertBroadcaster alerts;
    private final TransactionTemplate txn;

    private volatile boolean running;
    private final List<Thread> workers = new ArrayList<>();

    private final com.msval.governance.findings.FindingsService findings;

    public IntakeService(MsvalProperties cfg, ServiceRepo services, LifecycleRepo lifecycle,
            EvaluationRepo evaluations, EnvironmentRepo environments, TopologyRepo topology,
            EvaluationCommitter committer, AlertBroadcaster alerts, TransactionTemplate txn,
            com.msval.governance.findings.FindingsService findings) {
        this.findings = findings;
        this.cfg = cfg;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, cfg.queueBound()));
        this.services = services;
        this.lifecycle = lifecycle;
        this.evaluations = evaluations;
        this.environments = environments;
        this.topology = topology;
        this.committer = committer;
        this.alerts = alerts;
        this.txn = txn;
    }

    /** Gateway entry: blocking put — the caller's reader thread stalls when full (FLOW-006). */
    public void submit(JsonNode forwardEnvelope) throws InterruptedException {
        queue.put(forwardEnvelope);
    }

    public int queueDepth() {
        return queue.size();
    }

    // ----------------------------------------------------------------- workers

    @Override
    public void start() {
        running = true;
        int n = 2 * Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(this::workLoop, "msval-intake-" + i);
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
        log.info("intake workers started: {} (queue bound {})", n, cfg.queueBound());
    }

    private void workLoop() {
        while (running) {
            JsonNode frame;
            try {
                frame = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                dispatch(frame);
            } catch (Exception e) {
                log.error("intake dispatch failed: {}", e.getMessage(), e);
                alerts.emit("SYSTEM", null, null, null, null,
                        "intake dispatch failed: " + e.getMessage());
            }
        }
    }

    public void dispatch(JsonNode frame) {
        JsonNode envelope = frame.has("envelope") ? frame.get("envelope") : frame;
        String kind = envelope.path("kind").asText("");
        switch (kind) {
            case "deployment" -> registerDeployment(envelope, frame);
            case "status_report" -> handleStatusReport(envelope);
            default -> log.warn("unknown event kind '{}' — dropped (event_id={})", kind,
                    envelope.path("event_id").asText(""));
        }
    }

    // ------------------------------------------------------------ deployment

    /** FLOW-010 / Txn A — the DD-013 normative SQL order, then the settle timer is live. */
    void registerDeployment(JsonNode envelope, JsonNode frame) {
        String sid = envelope.path("service_id").asText("");
        String ver = envelope.path("service_version").asText("");
        String env = envelope.path("environment").asText("");
        String eventId = envelope.path("event_id").asText("");
        boolean decommission = envelope.path("decommission").asBoolean(false);
        ServiceKey key = new ServiceKey(sid, ver, env);

        var envRow = environments.find(env);
        if (envRow.isEmpty()) {
            log.error("deployment {} for unknown environment '{}' — dropped", eventId, env);
            alerts.emit("SYSTEM", sid, env, null, null,
                    "deployment event for unknown environment '" + env + "'");
            return;
        }
        JsonNode approvedCdm = envelope.hasNonNull("approved_cdm") ? envelope.get("approved_cdm")
                : (frame.hasNonNull("cdm") ? frame.get("cdm") : null);
        String bundleVersion = frame.path("ingress_bundle_version").asText(null);
        int settleDelayS = envRow.get().settleDelayS();

        txn.executeWithoutResult(status -> {
            JsonNode svc = approvedCdm == null ? Jsonb.MAPPER.createObjectNode()
                    : approvedCdm.path("service");
            // 1. INSERT INTO services … ON CONFLICT DO NOTHING
            services.upsertService(sid, svc.path("name").asText(sid),
                    svc.path("layer").asText(null), svc.path("team").asText(null));
            // 2. INSERT INTO service_versions(…, declared_cdm) … ON CONFLICT DO UPDATE
            String digest = approvedCdm == null ? null
                    : approvedCdm.path("version").path("image_digest").asText(null);
            services.upsertVersion(sid, ver, digest, approvedCdm);
            // 3. SELECT … FOR UPDATE (may be absent)
            var existing = lifecycle.lockRow(key);
            String fromState = existing.map(LifecycleRepo.LifecycleRow::state).orElse(null);
            if (decommission) {
                // W2: decommission=true ⇒ lifecycle → Decommissioned (no settle, no supersede)
                lifecycle.upsertDeployed(key, null);
                lifecycle.setState(key, "Decommissioned", null);
                lifecycle.appendHistory(key, fromState, "Decommissioned", eventId);
            } else {
                // 4. INSERT lifecycle_states(state='Deployed', next_validation_at=…) ON CONFLICT UPDATE
                lifecycle.upsertDeployed(key, Instant.now().plusSeconds(settleDelayS));
                if (!"Deployed".equals(fromState)) {
                    lifecycle.appendHistory(key, fromState, "Deployed", eventId);
                }
                // 5. UPDATE … SET state='Superseded' for sibling versions
                for (LifecycleRepo.LifecycleRow s : lifecycle.supersede(key)) {
                    lifecycle.appendHistory(new ServiceKey(s.serviceId(), s.version(), s.environment()),
                            s.state(), "Superseded", eventId);
                }
            }
            // 6. INSERT evaluations receipt — verdict derived from the ingress's forwarded
            // intake_checks (rev: the Python intake stage runs the routed intake rule set,
            // e.g. symbolic_capacity; a failed check FAILs the intake evaluation and opens
            // findings through the normal IF-014 path). Fixes the TASK-028 integration gap.
            java.util.List<com.msval.governance.engine.CheckResult> failed = new java.util.ArrayList<>();
            java.util.List<String> evaluated = new java.util.ArrayList<>();
            for (JsonNode c : (Iterable<JsonNode>) () -> frame.path("intake_checks").elements()) {
                String rid = c.path("rule_id").asText("");
                if (!rid.isEmpty() && !evaluated.contains(rid)) evaluated.add(rid);
                if (!c.path("passed").asBoolean(true)) {
                    failed.add(new com.msval.governance.engine.CheckResult(rid,
                            c.path("path").asText(""), false,
                            c.path("reason_code").asText("RULE_FAILED"),
                            c.path("detail").asText("")));
                }
            }
            String intakeVerdict = failed.isEmpty() ? "PASS" : "FAIL";
            evaluations.insert(eventId, key, "intake", bundleVersion, intakeVerdict, null);
            if (!failed.isEmpty()) {
                var intakeDecision = new com.msval.governance.engine.Decision(
                        "FAIL", failed, java.util.List.of(), java.util.List.of(),
                        evaluated, bundleVersion == null ? "unversioned" : bundleVersion, "1.0", 0);
                committer.emitDelta(findings.applyEvaluation(intakeDecision, key, "intake"));
            }
            // 7. apply topology_delta to a new declared snapshot if present
            JsonNode delta = envelope.get("topology_delta");
            if (delta != null && !delta.isNull()) {
                applyTopologyDelta(key, delta);
            }
        });
        log.info("deployment {} registered: {} (settle {}s)", eventId, key,
                decommission ? "-" : settleDelayS);
    }

    private void applyTopologyDelta(ServiceKey key, JsonNode delta) {
        JsonNode latest = topology.latest(key.environment(), "declared").orElse(null);
        ObjectNode graph = latest != null && latest.isObject()
                ? (ObjectNode) latest.deepCopy() : Jsonb.MAPPER.createObjectNode();
        Set<String> nodes = new LinkedHashSet<>();
        for (JsonNode n : graph.path("nodes")) {
            nodes.add(n.asText());
        }
        nodes.add(key.serviceId());
        List<JsonNode> edges = new ArrayList<>();
        for (JsonNode e : graph.path("edges")) {
            edges.add(e);
        }
        for (JsonNode rm : delta.path("connections_remove")) {
            String to = rm.path("to").asText("");
            edges.removeIf(e -> key.serviceId().equals(e.path("from").asText())
                    && to.equals(e.path("to").asText()));
        }
        for (JsonNode add : delta.path("connections_add")) {
            ObjectNode edge = add.deepCopy();
            edge.put("from", key.serviceId());
            String to = edge.path("to").asText("");
            edges.removeIf(e -> key.serviceId().equals(e.path("from").asText())
                    && to.equals(e.path("to").asText()));
            edges.add(edge);
            nodes.add(to);
        }
        ArrayNode nodeArr = Jsonb.MAPPER.createArrayNode();
        nodes.forEach(nodeArr::add);
        ArrayNode edgeArr = Jsonb.MAPPER.createArrayNode();
        edges.forEach(edgeArr::add);
        graph.set("nodes", nodeArr);
        graph.set("edges", edgeArr);
        if (delta.hasNonNull("entry_point")) {
            graph.with("node_attrs").with(key.serviceId())
                    .put("entry_point", delta.get("entry_point").asBoolean());
        }
        topology.append(key.environment(), "declared", graph);
    }

    // ---------------------------------------------------------- status_report

    /** FLOW-012 — txn B (live snapshot + captured topology merge), then re-validation. */
    void handleStatusReport(JsonNode envelope) {
        String sid = envelope.path("service_id").asText("");
        String ver = envelope.path("service_version").asText("");
        String env = envelope.path("environment").asText("");
        ServiceKey key = new ServiceKey(sid, ver, env);
        JsonNode liveState = envelope.path("live_state");
        JsonNode observed = envelope.get("topology_observed");

        txn.executeWithoutResult(status -> {
            int updated = lifecycle.updateLiveReport(key, liveState);
            if (updated == 0) {
                log.info("status report for unregistered {} — live row absent (reconciler will flag)", key);
            }
            if (observed != null && !observed.isNull()) {
                mergeCaptured(key, observed);
            }
        });
        // Watch-first: change reports are the fast path; interval reports also re-validate
        // on receipt (TEST-011) — the sweep remains the safety net.
        committer.validate(key);
    }

    private void mergeCaptured(ServiceKey key, JsonNode observed) {
        JsonNode latest = topology.latest(key.environment(), "captured").orElse(null);
        ObjectNode graph = latest != null && latest.isObject()
                ? (ObjectNode) latest.deepCopy() : Jsonb.MAPPER.createObjectNode();
        Set<String> nodes = new LinkedHashSet<>();
        for (JsonNode n : graph.path("nodes")) {
            nodes.add(n.asText());
        }
        nodes.add(key.serviceId());
        List<JsonNode> edges = new ArrayList<>();
        for (JsonNode e : graph.path("edges")) {
            if (!key.serviceId().equals(e.path("from").asText())) {
                edges.add(e); // the report replaces this service's outgoing edges
            }
        }
        for (JsonNode c : observed.path("connections")) {
            ObjectNode edge = c.deepCopy();
            edge.put("from", key.serviceId());
            edges.add(edge);
        }
        ArrayNode nodeArr = Jsonb.MAPPER.createArrayNode();
        nodes.forEach(nodeArr::add);
        ArrayNode edgeArr = Jsonb.MAPPER.createArrayNode();
        edges.forEach(edgeArr::add);
        graph.set("nodes", nodeArr);
        graph.set("edges", edgeArr);
        topology.append(key.environment(), "captured", graph);
    }

    @Override
    public void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
