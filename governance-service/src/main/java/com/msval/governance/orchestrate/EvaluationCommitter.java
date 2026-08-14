package com.msval.governance.orchestrate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.engine.CheckResult;
import com.msval.governance.engine.Decision;
import com.msval.governance.engine.Engine;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.findings.FindingsDelta;
import com.msval.governance.findings.FindingsService;
import com.msval.governance.gateway.AlertBroadcaster;
import com.msval.governance.persist.EvaluationRepo;
import com.msval.governance.persist.LifecycleRepo;
import com.msval.governance.persist.ServiceKey;
import com.msval.governance.registry.RuleCache;
import com.msval.governance.registry.RuleSet;

import jakarta.annotation.PostConstruct;

/**
 * DD-013 — validate(key), the one evaluation used by FLOW-012/013/014:
 * assemble → engine.decide → inject drift (D4-4: BLOCK in production, WARN elsewhere)
 * → one txn {lock lifecycle row, evaluation append, state flip + history,
 * findings.applyEvaluation} → alerts from the delta.
 */
@Service
public class EvaluationCommitter {

    private static final Logger log = LoggerFactory.getLogger(EvaluationCommitter.class);

    private final ContextAssembler assembler;
    private final Engine engine;
    private final RuleCache ruleCache;
    private final LifecycleRepo lifecycle;
    private final EvaluationRepo evaluations;
    private final FindingsService findings;
    private final AlertBroadcaster alerts;
    private final TransactionTemplate txn;

    public EvaluationCommitter(ContextAssembler assembler, Engine engine, RuleCache ruleCache,
            LifecycleRepo lifecycle, EvaluationRepo evaluations, FindingsService findings,
            AlertBroadcaster alerts, TransactionTemplate txn) {
        this.assembler = assembler;
        this.engine = engine;
        this.ruleCache = ruleCache;
        this.lifecycle = lifecycle;
        this.evaluations = evaluations;
        this.findings = findings;
        this.alerts = alerts;
        this.txn = txn;
    }

    @PostConstruct
    void wireWaiverExpiryAlerts() {
        findings.onWaiverExpired(expired -> {
            for (FindingsService.ExpiredWaiver e : expired) {
                alerts.emit("WAIVER_EXPIRED", e.key().serviceId(), e.key().environment(),
                        e.ruleId(), null, "waiver " + e.waiverId() + " expired — finding "
                                + ("OPEN".equals(e.outcome()) ? "re-opened" : "resolved"));
            }
        });
    }

    /** Runtime re-validation of one (service, version, environment). */
    public Decision validate(ServiceKey key) {
        ContextAssembler.Assembled a;
        try {
            a = assembler.build(key);
        } catch (Exception e) {
            log.error("context assembly failed for {}: {}", key, e.getMessage());
            alerts.emit("SYSTEM", key.serviceId(), key.environment(), null, null,
                    "context assembly failed: " + e.getMessage());
            return null;
        }
        if (a == null) {
            log.info("nothing to validate for {} (no baseline, no live state)", key);
            return null;
        }
        RuleSet rules = ruleCache.ruleSetFor("runtime");
        String bundleVersion = rules.version() == null ? "none" : rules.version();
        long t0 = System.nanoTime();
        Decision engineDecision = engine.decide(a.doc(), rules.rules(), a.ctx(), bundleVersion);
        int durationMs = (int) ((System.nanoTime() - t0) / 1_000_000);
        Decision d = injectDrift(engineDecision, a, key.environment(), durationMs);

        FindingsDelta delta = txn.execute(status -> {
            var row = lifecycle.lockRow(key).orElse(null);
            if (row == null) {
                return null; // decommissioned/unknown meanwhile — nothing to commit
            }
            String evalId = UUID.randomUUID().toString();
            evaluations.insert(evalId, key, "runtime", bundleVersion, d.verdict(), durationMs);
            String newState = "PASS".equals(d.verdict()) ? "Validated" : "ValidationFailed";
            lifecycle.setState(key, newState, null);
            if (!newState.equals(row.state())) {
                lifecycle.appendHistory(key, row.state(), newState, evalId);
            }
            return findings.applyEvaluation(d, key, "evaluation");
        });
        if (delta != null) {
            emitDelta(delta);
        }
        if ("ERROR".equals(d.verdict())) {
            alerts.emit("SYSTEM", key.serviceId(), key.environment(), null, null,
                    "evaluation ERROR (engine fault) for " + key.serviceId() + ":" + key.version());
        }
        return d;
    }

    /**
     * DD-013 SweepService baseline-audit lane: evaluate the declared baseline alone;
     * findings carry detail.source="baseline-audit"; no state flip, no evaluation row.
     */
    public void baselineAudit(ServiceKey key, JsonNode declaredCdm, EvalContext ctx) {
        RuleSet rules = ruleCache.ruleSetFor("runtime");
        String bundleVersion = rules.version() == null ? "none" : rules.version();
        Decision d = engine.decide(declaredCdm, rules.rules(), ctx, bundleVersion);
        FindingsDelta delta = txn.execute(status ->
                findings.applyEvaluation(d, key, "baseline-audit"));
        if (delta != null) {
            emitDelta(delta);
        }
    }

    /** D4-4: drift results join the decision — BLOCK in production, WARN elsewhere. */
    static Decision injectDrift(Decision d, ContextAssembler.Assembled a, String environment,
            int durationMs) {
        List<CheckResult> blocking = new ArrayList<>(d.blocking());
        List<CheckResult> warnings = new ArrayList<>(d.warnings());
        List<String> evaluated = new ArrayList<>(d.evaluatedRules());
        evaluated.addAll(a.driftEvaluated());
        boolean production = "production".equals(environment);
        for (CheckResult drift : a.driftResults()) {
            if (production) {
                blocking.add(drift);
            } else {
                warnings.add(drift);
            }
        }
        String verdict = d.verdict();
        if (!"ERROR".equals(verdict)) {
            verdict = blocking.isEmpty() ? "PASS" : "FAIL";
        }
        return new Decision(verdict, List.copyOf(blocking), List.copyOf(warnings), d.waived(),
                List.copyOf(evaluated), d.evaluatedUnder(), d.cdmVersion(), durationMs);
    }

    /** W4 alert mapping: DRIFT-* → DRIFT, RECON-* → RECON, WARN severity → WARN, else VIOLATION. */
    public void emitDelta(FindingsDelta delta) {
        List<FindingsDelta.Touched> all = new ArrayList<>(delta.opened);
        all.addAll(delta.updated);
        for (FindingsDelta.Touched t : all) {
            if (!t.alert()) {
                continue; // matrix: WAIVED occ++ stays silent
            }
            String kind;
            if (t.ruleId().startsWith("DRIFT-")) {
                kind = "DRIFT";
            } else if (t.ruleId().startsWith("RECON-")) {
                kind = "RECON";
            } else if ("WARN".equals(t.severity())) {
                kind = "WARN";
            } else {
                kind = "VIOLATION";
            }
            alerts.emit(kind, t.serviceId(), t.environment(), t.ruleId(), t.severity(), t.message());
        }
    }
}
