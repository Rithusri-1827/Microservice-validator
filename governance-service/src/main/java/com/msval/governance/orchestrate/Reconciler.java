package com.msval.governance.orchestrate;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.msval.governance.config.MsvalProperties;
import com.msval.governance.findings.FindingsDelta;
import com.msval.governance.findings.FindingsService;
import com.msval.governance.persist.LifecycleRepo;
import com.msval.governance.persist.ServiceKey;

/**
 * DD-013 — Reconciler (REQ-019 estate inventory): the four queries below are the DD-013
 * normative SQL. Each hit becomes a synthetic finding (IF-014 reserved ids RECON-MISSING /
 * RECON-UNDECLARED / RECON-STALE / RECON-SKEW) in the uniform lifecycle; cleared anomalies
 * auto-resolve.
 *
 * <p>One correction over the DD text (verified against Postgres 16): in the MISSING /
 * UNDECLARED subqueries the {@code LIMIT 1} must select the newest *snapshot* before the
 * set-returning {@code jsonb_array_elements_text} expands nodes — the DD's inline form
 * would truncate the node list to a single row.
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    // MISSING_DEPLOYMENT: declared topology nodes without a live lifecycle row
    private static final String Q_MISSING = """
            SELECT n.node FROM (
              SELECT jsonb_array_elements_text(t.graph->'nodes') node FROM (
                SELECT graph FROM topology_snapshots
                WHERE environment = ? AND origin = 'declared' ORDER BY at DESC LIMIT 1) t) n
            LEFT JOIN lifecycle_states ls ON ls.service_id = n.node AND ls.environment = ?
              AND ls.state IN ('Deployed','Validated','ValidationFailed')
            WHERE ls.service_id IS NULL
            """;

    // UNDECLARED_SERVICE: inverse join, captured vs declared
    private static final String Q_UNDECLARED = """
            SELECT n.node FROM (
              SELECT jsonb_array_elements_text(t.graph->'nodes') node FROM (
                SELECT graph FROM topology_snapshots
                WHERE environment = ? AND origin = 'captured' ORDER BY at DESC LIMIT 1) t) n
            LEFT JOIN (
              SELECT jsonb_array_elements_text(t.graph->'nodes') node FROM (
                SELECT graph FROM topology_snapshots
                WHERE environment = ? AND origin = 'declared' ORDER BY at DESC LIMIT 1) t) d
              ON d.node = n.node
            WHERE d.node IS NULL
            """;

    // STALE_SERVICE (verbatim DD-013)
    private static final String Q_STALE = """
            SELECT service_id, version FROM lifecycle_states WHERE environment = ?
              AND state IN ('Deployed','Validated','ValidationFailed')
              AND last_report_at < now() - make_interval(secs => ? * ?)
            """;

    // VERSION_SKEW (verbatim DD-013)
    private static final String Q_SKEW = """
            SELECT service_id, version FROM lifecycle_states WHERE environment = ?
              AND state = 'Superseded' AND last_report_at > now() - make_interval(secs => ? * 2)
            """;

    private final JdbcTemplate jdbc;
    private final MsvalProperties cfg;
    private final FindingsService findings;
    private final LifecycleRepo lifecycle;
    private final EvaluationCommitter committer;
    private final TransactionTemplate txn;

    public Reconciler(JdbcTemplate jdbc, MsvalProperties cfg, FindingsService findings,
            LifecycleRepo lifecycle, EvaluationCommitter committer, TransactionTemplate txn) {
        this.jdbc = jdbc;
        this.cfg = cfg;
        this.findings = findings;
        this.lifecycle = lifecycle;
        this.committer = committer;
        this.txn = txn;
    }

    /** One inventory pass for one environment; emits RECON alerts from the deltas. */
    public void run(String environment) {
        try {
            apply(environment, "RECON-MISSING", "MISSING_DEPLOYMENT", missing(environment));
            apply(environment, "RECON-UNDECLARED", "UNDECLARED_SERVICE", undeclared(environment));
            List<FindingsService.SyntheticHit> stale = stale(environment);
            for (FindingsService.SyntheticHit hit : stale) {
                lifecycle.setStale(new ServiceKey(hit.serviceId(), hit.version(), environment));
            }
            apply(environment, "RECON-STALE", "STALE_SERVICE", stale);
            apply(environment, "RECON-SKEW", "VERSION_SKEW", skew(environment));
        } catch (Exception e) {
            log.error("reconciliation failed for {}: {}", environment, e.getMessage());
        }
    }

    List<FindingsService.SyntheticHit> missing(String environment) {
        List<FindingsService.SyntheticHit> hits = new ArrayList<>();
        // Declared node with no registered version — the key's version component has no
        // reported value; "-" is the documented placeholder (IF-014 covers UNDECLARED only).
        for (String node : jdbc.queryForList(Q_MISSING, String.class, environment, environment)) {
            hits.add(new FindingsService.SyntheticHit(node, "-",
                    "declared in topology but never deployed in " + environment));
        }
        return hits;
    }

    List<FindingsService.SyntheticHit> undeclared(String environment) {
        List<FindingsService.SyntheticHit> hits = new ArrayList<>();
        for (String node : jdbc.queryForList(Q_UNDECLARED, String.class, environment, environment)) {
            // IF-014: the key's version component is the reported version string verbatim.
            String version = jdbc.queryForList("""
                    SELECT version FROM lifecycle_states WHERE service_id = ? AND environment = ?
                    ORDER BY last_report_at DESC NULLS LAST LIMIT 1
                    """, String.class, node, environment).stream().findFirst().orElse("-");
            hits.add(new FindingsService.SyntheticHit(node, version,
                    "observed in captured topology but not declared in " + environment));
        }
        return hits;
    }

    List<FindingsService.SyntheticHit> stale(String environment) {
        return jdbc.query(Q_STALE, (rs, i) -> new FindingsService.SyntheticHit(
                        rs.getString(1), rs.getString(2),
                        "no status report within " + cfg.reportIntervalS() * cfg.staleFactor() + "s"),
                environment, cfg.reportIntervalS(), cfg.staleFactor());
    }

    List<FindingsService.SyntheticHit> skew(String environment) {
        return jdbc.query(Q_SKEW, (rs, i) -> new FindingsService.SyntheticHit(
                        rs.getString(1), rs.getString(2),
                        "superseded version still reporting (skew window " + cfg.reportIntervalS() * 2 + "s)"),
                environment, cfg.reportIntervalS());
    }

    private void apply(String environment, String ruleId, String reasonCode,
            List<FindingsService.SyntheticHit> hits) {
        FindingsDelta delta = txn.execute(status ->
                findings.applySynthetic(environment, ruleId, reasonCode, hits));
        if (delta != null) {
            committer.emitDelta(delta);
        }
    }
}
