package com.msval.governance.persist;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * DD-017 — lifecycle_states + lifecycle_history (DAT-005). Normative SQL from DD-013:
 * FOR UPDATE row locks, ON CONFLICT upserts, SKIP LOCKED settle poll. Every state change
 * is mirrored into lifecycle_history by the caller's transaction (Part IV completions).
 */
@Repository
public class LifecycleRepo {

    public record LifecycleRow(String serviceId, String version, String environment, String state,
            Instant lastReportAt, Instant nextValidationAt, JsonNode liveSnapshot, boolean stale) {
    }

    private static final RowMapper<LifecycleRow> ROW = (rs, i) -> new LifecycleRow(
            rs.getString("service_id"), rs.getString("version"), rs.getString("environment"),
            rs.getString("state"),
            instant(rs.getTimestamp("last_report_at")),
            instant(rs.getTimestamp("next_validation_at")),
            Jsonb.read(rs.getString("live_snapshot")), rs.getBoolean("stale"));

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private final JdbcTemplate jdbc;

    public LifecycleRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Txn A step 3 / validate(key) lock: SELECT … FOR UPDATE; empty when the row is absent. */
    public Optional<LifecycleRow> lockRow(ServiceKey key) {
        return jdbc.query("SELECT * FROM lifecycle_states WHERE service_id = ? AND version = ? "
                        + "AND environment = ? FOR UPDATE", ROW,
                key.serviceId(), key.version(), key.environment()).stream().findFirst();
    }

    public Optional<LifecycleRow> find(ServiceKey key) {
        return jdbc.query("SELECT * FROM lifecycle_states WHERE service_id = ? AND version = ? "
                        + "AND environment = ?", ROW,
                key.serviceId(), key.version(), key.environment()).stream().findFirst();
    }

    /** Txn A step 4: (re)deploy — state Deployed, settle timer persisted (restart-safe). */
    public void upsertDeployed(ServiceKey key, Instant nextValidationAt) {
        jdbc.update("""
                INSERT INTO lifecycle_states(service_id, version, environment, state, next_validation_at)
                VALUES (?,?,?, 'Deployed', ?)
                ON CONFLICT (service_id, version, environment) DO UPDATE SET
                  state = 'Deployed', next_validation_at = EXCLUDED.next_validation_at
                """, key.serviceId(), key.version(), key.environment(),
                nextValidationAt == null ? null : Timestamp.from(nextValidationAt));
    }

    /** validate(key) commit / decommission: set state, clear (or move) the settle timer. */
    public void setState(ServiceKey key, String state, Instant nextValidationAt) {
        jdbc.update("UPDATE lifecycle_states SET state = ?, next_validation_at = ? "
                        + "WHERE service_id = ? AND version = ? AND environment = ?",
                state, nextValidationAt == null ? null : Timestamp.from(nextValidationAt),
                key.serviceId(), key.version(), key.environment());
    }

    /** Txn A step 5 (normative): other versions of the service in this env → Superseded. */
    public List<LifecycleRow> supersede(ServiceKey key) {
        return jdbc.query("""
                UPDATE lifecycle_states SET state = 'Superseded'
                WHERE service_id = ? AND environment = ? AND version <> ?
                  AND state IN ('Validated','ValidationFailed','Deployed')
                RETURNING *
                """, ROW, key.serviceId(), key.environment(), key.version());
    }

    /**
     * SettleScheduler poll (DD-013): claim due rows with SKIP LOCKED, clearing the timer in
     * the same statement so a concurrent poller (or a crash before validate) never double-runs.
     */
    public List<ServiceKey> claimDue(int limit) {
        return jdbc.query("""
                UPDATE lifecycle_states SET next_validation_at = NULL
                WHERE (service_id, version, environment) IN (
                  SELECT service_id, version, environment FROM lifecycle_states
                  WHERE next_validation_at <= now() LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING service_id, version, environment
                """, (rs, i) -> new ServiceKey(rs.getString(1), rs.getString(2), rs.getString(3)), limit);
    }

    /** Status-report txn B: live snapshot + freshness; a fresh report clears the stale flag. */
    public int updateLiveReport(ServiceKey key, JsonNode liveSnapshot) {
        return jdbc.update("UPDATE lifecycle_states SET live_snapshot = ?::jsonb, "
                        + "last_report_at = now(), stale = false "
                        + "WHERE service_id = ? AND version = ? AND environment = ?",
                Jsonb.write(liveSnapshot), key.serviceId(), key.version(), key.environment());
    }

    /** Reconciler STALE_SERVICE side effect: flag the row (DD-013). */
    public void setStale(ServiceKey key) {
        jdbc.update("UPDATE lifecycle_states SET stale = true "
                        + "WHERE service_id = ? AND version = ? AND environment = ?",
                key.serviceId(), key.version(), key.environment());
    }

    /** Sweep scan (index ls_env): rows to re-audit in one environment. */
    public List<LifecycleRow> inStates(String environment, List<String> states) {
        return jdbc.query("SELECT * FROM lifecycle_states WHERE environment = ? "
                        + "AND state = ANY(?::text[]) ORDER BY service_id, version", ROW,
                environment, Jsonb.textArray(states));
    }

    /** Promotion history for the assembler (F10): this version's state per environment. */
    public List<LifecycleRow> historyOf(String serviceId, String version) {
        return jdbc.query("SELECT * FROM lifecycle_states WHERE service_id = ? AND version = ? "
                + "ORDER BY environment", ROW, serviceId, version);
    }

    /** Console tree (W5 /services/tree): all rows, optionally scoped to one environment. */
    public List<LifecycleRow> treeRows(String environment) {
        if (environment == null || environment.isBlank()) {
            return jdbc.query("SELECT * FROM lifecycle_states ORDER BY service_id, version, environment", ROW);
        }
        return jdbc.query("SELECT * FROM lifecycle_states WHERE environment = ? "
                + "ORDER BY service_id, version", ROW, environment);
    }

    /** Part IV: every state change appends a history row inside the same transaction. */
    public void appendHistory(ServiceKey key, String fromState, String toState, String eventId) {
        jdbc.update("INSERT INTO lifecycle_history(service_id, version, environment, from_state, "
                        + "to_state, event_id) VALUES (?,?,?,?,?,?)",
                key.serviceId(), key.version(), key.environment(), fromState, toState, eventId);
    }
}
