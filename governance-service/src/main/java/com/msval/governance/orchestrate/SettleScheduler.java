package com.msval.governance.orchestrate;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.msval.governance.persist.LifecycleRepo;
import com.msval.governance.persist.ServiceKey;

/**
 * DD-013 — SettleScheduler: no in-memory timers as source of truth. Poll every 10 s:
 * claim rows with next_validation_at ≤ now (LIMIT 50, FOR UPDATE SKIP LOCKED — the claim
 * also clears the timer, keeping concurrent pollers and restarts safe), then validate each.
 */
@Component
public class SettleScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettleScheduler.class);

    private final LifecycleRepo lifecycle;
    private final EvaluationCommitter committer;
    private final TransactionTemplate txn;

    public SettleScheduler(LifecycleRepo lifecycle, EvaluationCommitter committer,
            TransactionTemplate txn) {
        this.lifecycle = lifecycle;
        this.committer = committer;
        this.txn = txn;
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 10_000)
    public void poll() {
        try {
            List<ServiceKey> due = txn.execute(status -> lifecycle.claimDue(50));
            if (due == null || due.isEmpty()) {
                return;
            }
            log.info("settle poll: {} due", due.size());
            for (ServiceKey key : due) {
                committer.validate(key);
            }
        } catch (Exception e) {
            log.error("settle poll failed: {}", e.getMessage());
        }
    }
}
