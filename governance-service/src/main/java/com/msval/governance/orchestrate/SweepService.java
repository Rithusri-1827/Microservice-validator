package com.msval.governance.orchestrate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.persist.EnvironmentRepo;
import com.msval.governance.persist.LifecycleRepo;
import com.msval.governance.persist.ServiceKey;
import com.msval.governance.persist.ServiceRepo;

/**
 * DD-013 — SweepService (per env, every SWEEP_INTERVAL_S): declared-baseline audit
 * (source="baseline-audit") first, live re-audit of Validated/ValidationFailed rows last
 * (live truth gets the final word within a tick), then the Reconciler on the same tick.
 */
@Component
public class SweepService {

    private static final Logger log = LoggerFactory.getLogger(SweepService.class);

    private final EnvironmentRepo environments;
    private final LifecycleRepo lifecycle;
    private final ServiceRepo services;
    private final EvaluationCommitter committer;
    private final Reconciler reconciler;

    public SweepService(EnvironmentRepo environments, LifecycleRepo lifecycle,
            ServiceRepo services, EvaluationCommitter committer, Reconciler reconciler) {
        this.environments = environments;
        this.lifecycle = lifecycle;
        this.services = services;
        this.committer = committer;
        this.reconciler = reconciler;
    }

    @Scheduled(initialDelayString = "${msval.sweep-interval-s}",
            fixedDelayString = "${msval.sweep-interval-s}", timeUnit = TimeUnit.SECONDS)
    public void scheduledSweep() {
        try {
            sweepAll();
        } catch (Exception e) {
            log.error("sweep failed: {}", e.getMessage());
        }
    }

    public void sweepAll() {
        for (EnvironmentRepo.EnvironmentRow env : environments.all()) {
            sweep(env.name());
        }
    }

    public void sweep(String environment) {
        // Baseline audit: every current version's declared CDM against the active runtime set.
        for (LifecycleRepo.LifecycleRow row : lifecycle.inStates(environment,
                List.of("Deployed", "Validated", "ValidationFailed"))) {
            ServiceKey key = new ServiceKey(row.serviceId(), row.version(), row.environment());
            JsonNode declared = services.declaredCdm(key.serviceId(), key.version()).orElse(null);
            if (declared != null) {
                EvalContext ctx = new EvalContext("runtime", environment, null, null, null,
                        declared, null, List.of(), Instant.now().toString());
                committer.baselineAudit(key, declared, ctx);
            }
        }
        // Live re-audit of settled rows (rule changes surface without waiting for a report).
        for (LifecycleRepo.LifecycleRow row : lifecycle.inStates(environment,
                List.of("Validated", "ValidationFailed"))) {
            committer.validate(new ServiceKey(row.serviceId(), row.version(), row.environment()));
        }
        // Reconciliation runs on the same tick (DD-013).
        reconciler.run(environment);
    }
}
