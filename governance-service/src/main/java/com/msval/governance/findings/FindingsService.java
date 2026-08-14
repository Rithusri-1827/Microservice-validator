package com.msval.governance.findings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.engine.CheckResult;
import com.msval.governance.engine.Decision;
import com.msval.governance.engine.Waiver;
import com.msval.governance.persist.EvaluationRepo;
import com.msval.governance.persist.Jsonb;
import com.msval.governance.persist.ServiceKey;
import com.msval.governance.persist.ViolationRepo;
import com.msval.governance.persist.WaiverRepo;
import com.msval.governance.support.HttpError;

/**
 * DD-015 / IF-014 — violation lifecycle + waivers. Every move goes through
 * {@link Transitions}; applyEvaluation runs inside the orchestrator's commit transaction.
 */
@Service
public class FindingsService {

    private static final Logger log = LoggerFactory.getLogger(FindingsService.class);
    private static final long WAIVER_CACHE_MS = 5_000; // IF-014: 5 s cache

    private final ViolationRepo violations;
    private final WaiverRepo waivers;
    private final EvaluationRepo evaluations;
    private final TransactionTemplate txn;

    private record CachedWaivers(long at, List<Waiver> rows) {
    }

    private final ConcurrentHashMap<String, CachedWaivers> waiverCache = new ConcurrentHashMap<>();

    /** Expired-waiver outcomes the sweep hands to the alert path (kind WAIVER_EXPIRED). */
    public record ExpiredWaiver(long waiverId, String ruleId, ServiceKey key, String outcome) {
    }

    public FindingsService(ViolationRepo violations, WaiverRepo waivers,
            EvaluationRepo evaluations, TransactionTemplate txn) {
        this.violations = violations;
        this.waivers = waivers;
        this.evaluations = evaluations;
        this.txn = txn;
    }

    // ------------------------------------------------------------- IF-014 reads

    /** activeWaivers(service_version, environment) — 5 s in-memory cache (IF-014). */
    public List<Waiver> activeWaivers(ServiceKey key) {
        String k = key.serviceId() + "|" + key.version() + "|" + key.environment();
        long now = System.currentTimeMillis();
        CachedWaivers c = waiverCache.get(k);
        if (c != null && now - c.at() < WAIVER_CACHE_MS) {
            return c.rows();
        }
        List<Waiver> rows = waivers.activeFor(key);
        waiverCache.put(k, new CachedWaivers(now, rows));
        return rows;
    }

    // -------------------------------------------------------- applyEvaluation

    /**
     * DD-015 applyEvaluation (called inside the orchestrator's txn): upsert every failed
     * result, upsert waived results silently, auto-resolve evaluated-and-now-passing rules.
     */
    public FindingsDelta applyEvaluation(Decision d, ServiceKey key, String source) {
        FindingsDelta delta = new FindingsDelta();
        for (CheckResult r : d.blocking()) {
            apply(delta, r, key, "BLOCK", source, null);
        }
        for (CheckResult r : d.warnings()) {
            apply(delta, r, key, "WARN", source, null);
        }
        for (Decision.Waived w : d.waived()) {
            apply(delta, w.result(), key, "BLOCK", source, w.waiverId());
        }
        Set<String> failed = new LinkedHashSet<>();
        for (CheckResult r : d.blocking()) {
            failed.add(r.ruleId());
        }
        for (CheckResult r : d.warnings()) {
            failed.add(r.ruleId());
        }
        for (Decision.Waived w : d.waived()) {
            failed.add(w.result().ruleId());
        }
        List<String> failedIds = new ArrayList<>(failed);
        if (failedIds.isEmpty()) {
            failedIds.add("__none__"); // ANY() needs a non-empty array
        }
        for (ViolationRepo.ViolationRow row : violations.autoResolve(key, d.evaluatedRules(), failedIds)) {
            delta.autoResolved.add(new FindingsDelta.Touched(row.id(), row.ruleId(), key.serviceId(),
                    key.version(), key.environment(), "RESOLVED", severityOf(row.detail()), false,
                    "auto-resolved"));
        }
        return delta;
    }

    /**
     * Reconciliation lane (DD-013): synthetic RECON-* results share the dedup key and
     * lifecycle; hits upsert, non-hits of the same rule id in the environment auto-resolve.
     */
    public FindingsDelta applySynthetic(String environment, String ruleId, String reasonCode,
            List<SyntheticHit> hits) {
        FindingsDelta delta = new FindingsDelta();
        List<String> hitKeys = new ArrayList<>();
        for (SyntheticHit hit : hits) {
            ServiceKey key = new ServiceKey(hit.serviceId(), hit.version(), environment);
            hitKeys.add(hit.serviceId() + "|" + hit.version());
            ObjectNode detail = Jsonb.MAPPER.createObjectNode();
            detail.put("reason_code", reasonCode);
            detail.put("detail", hit.detail());
            detail.put("severity", "WARN");
            detail.put("source", "reconciliation");
            ViolationRepo.Upsert up = violations.upsert(ruleId, key, "OPEN", detail, null);
            record(delta, up, ruleId, key, "WARN", hit.detail());
        }
        if (hitKeys.isEmpty()) {
            hitKeys.add("__none__");
        }
        for (ViolationRepo.ViolationRow row : violations.resolveSyntheticMisses(environment, ruleId, hitKeys)) {
            delta.autoResolved.add(new FindingsDelta.Touched(row.id(), row.ruleId(), row.serviceId(),
                    row.version(), environment, "RESOLVED", "WARN", false, "reconciliation clear"));
        }
        return delta;
    }

    public record SyntheticHit(String serviceId, String version, String detail) {
    }

    private void apply(FindingsDelta delta, CheckResult r, ServiceKey key, String severity,
            String source, Long waiverId) {
        ObjectNode detail = Jsonb.MAPPER.createObjectNode();
        detail.put("reason_code", r.reasonCode());
        detail.put("detail", r.detail());
        detail.put("path", r.path());
        detail.put("severity", severity);
        detail.put("source", source);
        if (r.observed() != null) {
            detail.set("observed", r.observed());
        }
        String initial = waiverId != null ? "WAIVED" : "OPEN";
        ViolationRepo.Upsert up = violations.upsert(r.ruleId(), key, initial, detail, waiverId);
        record(delta, up, r.ruleId(), key, severity, r.detail());
    }

    private void record(FindingsDelta delta, ViolationRepo.Upsert up, String ruleId, ServiceKey key,
            String severity, String message) {
        if (up.inserted()) {
            boolean alert = !"WAIVED".equals(up.status()); // fresh WAIVED row: engine-waived, silent
            delta.opened.add(new FindingsDelta.Touched(up.id(), ruleId, key.serviceId(),
                    key.version(), key.environment(), up.status(), severity, alert, message));
        } else {
            Transitions.Outcome o = Transitions.next(
                    Transitions.Status.valueOf(up.status()), Transitions.Trigger.EVAL_FAIL);
            delta.updated.add(new FindingsDelta.Touched(up.id(), ruleId, key.serviceId(),
                    key.version(), key.environment(), up.status(), severity, o.alert(), message));
        }
    }

    private static String severityOf(JsonNode detail) {
        return detail == null ? "BLOCK" : detail.path("severity").asText("BLOCK");
    }

    // ------------------------------------------------------- operator actions

    /** W5 POST /violations/{id}/status — matrix columns "operator ACK/RESOLVE". */
    public ViolationRepo.ViolationRow setStatus(long id, String to) {
        Transitions.Trigger trigger = switch (to) {
            case "ACKNOWLEDGED" -> Transitions.Trigger.ACK;
            case "RESOLVED" -> Transitions.Trigger.RESOLVE;
            default -> throw HttpError.unprocessable("to must be ACKNOWLEDGED|RESOLVED", List.of());
        };
        return txn.execute(status -> {
            ViolationRepo.ViolationRow row = violations.lockById(id)
                    .orElseThrow(() -> HttpError.notFound("no violation " + id));
            Transitions.Outcome o = Transitions.next(Transitions.Status.valueOf(row.status()), trigger);
            if (o.kind() == Transitions.Kind.CONFLICT) {
                throw HttpError.conflict("illegal transition " + row.status() + " -> " + to);
            }
            violations.updateStatus(id, o.target().name(), null);
            return violations.byId(id).orElseThrow();
        });
    }

    /** W5 POST /violations/{id}/waive — creates the waiver and moves the finding to WAIVED. */
    public long waive(long id, Instant expiresAt, String approvedBy, String reason) {
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw HttpError.unprocessable("expires_at must be in the future", List.of());
        }
        Long waiverId = txn.execute(status -> {
            ViolationRepo.ViolationRow row = violations.lockById(id)
                    .orElseThrow(() -> HttpError.notFound("no violation " + id));
            Transitions.Outcome o = Transitions.next(
                    Transitions.Status.valueOf(row.status()), Transitions.Trigger.WAIVE);
            if (o.kind() == Transitions.Kind.CONFLICT) {
                throw HttpError.conflict("illegal transition " + row.status() + " -> WAIVED");
            }
            ServiceKey key = new ServiceKey(row.serviceId(), row.version(), row.environment());
            long wid = waivers.insert(row.ruleId(), key, approvedBy, reason, expiresAt);
            violations.updateStatus(id, "WAIVED", wid);
            return wid;
        });
        waiverCache.clear(); // fresh waiver visible to the next evaluation immediately
        return waiverId == null ? 0 : waiverId;
    }

    // ----------------------------------------------------------- expiry sweep

    /**
     * DD-015 waiver sweep (hourly): expire due waivers (SKIP LOCKED), apply the matrix's
     * "sweep expiry" column per linked finding, hand outcomes to the alert path.
     */
    public List<ExpiredWaiver> sweepExpiredWaivers() {
        List<ExpiredWaiver> out = txn.execute(status -> {
            List<ExpiredWaiver> results = new ArrayList<>();
            for (WaiverRepo.WaiverRow w : waivers.expireDue()) {
                for (ViolationRepo.ViolationRow row : violations.byWaiver(w.id())) {
                    Transitions.Outcome o = Transitions.next(
                            Transitions.Status.valueOf(row.status()), Transitions.Trigger.SWEEP_EXPIRY);
                    if (o.kind() != Transitions.Kind.EXPIRY_CHECK) {
                        continue;
                    }
                    ServiceKey key = new ServiceKey(row.serviceId(), row.version(), row.environment());
                    // OPEN iff the last runtime evaluation still failed this rule: a passing
                    // evaluation would have auto-resolved the WAIVED row (matrix EVAL_PASS),
                    // and one *after* last_seen that skipped the rule means it no longer fails.
                    boolean lastEvalFails = evaluations.latestRuntime(key)
                            .map(e -> !e.at().isAfter(row.lastSeen()))
                            .orElse(true);
                    String target = lastEvalFails ? "OPEN" : "RESOLVED";
                    violations.updateStatus(row.id(), target, null);
                    results.add(new ExpiredWaiver(w.id(), row.ruleId(), key, target));
                }
            }
            return results;
        });
        waiverCache.clear();
        return out == null ? List.of() : out;
    }

    @Scheduled(initialDelay = 3600, fixedDelay = 3600, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    public void scheduledWaiverSweep() {
        try {
            List<ExpiredWaiver> expired = sweepExpiredWaivers();
            if (!expired.isEmpty()) {
                log.info("waiver sweep: {} expired", expired.size());
                if (expiryListener != null) {
                    expiryListener.accept(expired);
                }
            }
        } catch (Exception e) {
            log.error("waiver sweep failed: {}", e.getMessage());
        }
    }

    private java.util.function.Consumer<List<ExpiredWaiver>> expiryListener;

    /** The gateway's alert path registers here (WAIVER_EXPIRED frames, W4). */
    public void onWaiverExpired(java.util.function.Consumer<List<ExpiredWaiver>> listener) {
        this.expiryListener = listener;
    }
}
