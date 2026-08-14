package com.msval.governance.persist;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * DD-017 — violations (DAT-008). The unique-open partial index {@code v_open} is the dedup
 * key (rule_id, service_id, version, environment) — synthetic RECON- and DRIFT- ids
 * participate in the same key (IF-014 rev 9). All statements are prepared (NFR-004).
 */
@Repository
public class ViolationRepo {

    public record ViolationRow(long id, String ruleId, String serviceId, String version,
            String environment, String status, Instant firstSeen, Instant lastSeen,
            int occurrences, JsonNode detail, Long waiverId) {
    }

    /** One upsert against v_open: {@code inserted} = fresh row (xmax=0), else occurrence bump. */
    public record Upsert(long id, String status, int occurrences, boolean inserted) {
    }

    private static final RowMapper<ViolationRow> ROW = (rs, i) -> new ViolationRow(
            rs.getLong("id"), rs.getString("rule_id"), rs.getString("service_id"),
            rs.getString("version"), rs.getString("environment"), rs.getString("status"),
            rs.getTimestamp("first_seen").toInstant(), rs.getTimestamp("last_seen").toInstant(),
            rs.getInt("occurrences"), Jsonb.read(rs.getString("detail")),
            (Long) rs.getObject("waiver_id"));

    private final JdbcTemplate jdbc;

    public ViolationRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * DD-015 applyEvaluation upsert: new failure opens a row; an existing unresolved row keeps
     * its status (matrix row = from-state) and gains occurrences+1 / last_seen / fresh detail.
     */
    public Upsert upsert(String ruleId, ServiceKey key, String initialStatus, JsonNode detail,
            Long waiverId) {
        return jdbc.queryForObject("""
                INSERT INTO violations(rule_id, service_id, version, environment, status, detail, waiver_id)
                VALUES (?,?,?,?,?,?::jsonb,?)
                ON CONFLICT (rule_id, service_id, version, environment)
                  WHERE status IN ('OPEN','ACKNOWLEDGED','WAIVED')
                DO UPDATE SET occurrences = violations.occurrences + 1, last_seen = now(),
                              detail = EXCLUDED.detail,
                              waiver_id = COALESCE(violations.waiver_id, EXCLUDED.waiver_id)
                RETURNING id, status, occurrences, (xmax = 0) AS inserted
                """, (rs, i) -> new Upsert(rs.getLong("id"), rs.getString("status"),
                        rs.getInt("occurrences"), rs.getBoolean("inserted")),
                ruleId, key.serviceId(), key.version(), key.environment(), initialStatus,
                Jsonb.write(detail), waiverId);
    }

    /** DD-015 auto-resolve: open findings whose rule was evaluated and did not fail. */
    public List<ViolationRow> autoResolve(ServiceKey key, List<String> evaluatedIds, List<String> failedIds) {
        if (evaluatedIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                UPDATE violations SET status = 'RESOLVED', last_seen = now()
                WHERE service_id = ? AND version = ? AND environment = ?
                  AND status IN ('OPEN','ACKNOWLEDGED','WAIVED')
                  AND rule_id = ANY(?::text[]) AND NOT (rule_id = ANY(?::text[]))
                RETURNING *
                """, ROW, key.serviceId(), key.version(), key.environment(),
                Jsonb.textArray(evaluatedIds), Jsonb.textArray(failedIds));
    }

    /** Reconciler auto-resolve: open findings of one synthetic rule no longer among the hits. */
    public List<ViolationRow> resolveSyntheticMisses(String environment, String ruleId, List<String> hitKeys) {
        return jdbc.query("""
                UPDATE violations SET status = 'RESOLVED', last_seen = now()
                WHERE environment = ? AND rule_id = ?
                  AND status IN ('OPEN','ACKNOWLEDGED','WAIVED')
                  AND NOT ((service_id || '|' || version) = ANY(?::text[]))
                RETURNING *
                """, ROW, environment, ruleId, Jsonb.textArray(hitKeys));
    }

    public Optional<ViolationRow> byId(long id) {
        return jdbc.query("SELECT * FROM violations WHERE id = ?", ROW, id).stream().findFirst();
    }

    public Optional<ViolationRow> lockById(long id) {
        return jdbc.query("SELECT * FROM violations WHERE id = ? FOR UPDATE", ROW, id).stream().findFirst();
    }

    public List<ViolationRow> byWaiver(long waiverId) {
        return jdbc.query("SELECT * FROM violations WHERE waiver_id = ? AND status = 'WAIVED'", ROW, waiverId);
    }

    public void updateStatus(long id, String status, Long waiverId) {
        jdbc.update("UPDATE violations SET status = ?, last_seen = now(), "
                + "waiver_id = COALESCE(?, waiver_id) WHERE id = ?", status, waiverId, id);
    }

    /** W5 GET /violations — findings board page (index v_board). */
    public List<ViolationRow> board(String service, String env, String status, String rule,
            int page, int size) {
        return jdbc.query("""
                SELECT * FROM violations
                WHERE (?::text IS NULL OR service_id = ?) AND (?::text IS NULL OR environment = ?)
                  AND (?::text IS NULL OR status = ?) AND (?::text IS NULL OR rule_id = ?)
                ORDER BY last_seen DESC LIMIT ? OFFSET ?
                """, ROW, service, service, env, env, status, status, rule, rule, size, page * size);
    }

    public long boardTotal(String service, String env, String status, String rule) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM violations
                WHERE (?::text IS NULL OR service_id = ?) AND (?::text IS NULL OR environment = ?)
                  AND (?::text IS NULL OR status = ?) AND (?::text IS NULL OR rule_id = ?)
                """, Long.class, service, service, env, env, status, status, rule, rule);
        return n == null ? 0 : n;
    }

    /** W4 snapshot: per-environment unresolved counts {env: {OPEN: n, ACKNOWLEDGED: n, WAIVED: n}}. */
    public Map<String, Map<String, Integer>> openCounts() {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        jdbc.query("SELECT environment, status, count(*) n FROM violations "
                + "WHERE status IN ('OPEN','ACKNOWLEDGED','WAIVED') "
                + "GROUP BY environment, status ORDER BY environment", rs -> {
            out.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
                    .put(rs.getString(2), rs.getInt(3));
        });
        return out;
    }

    /** Unresolved finding count per (service, version, environment) — console tree badges. */
    public Map<String, Integer> openCountsByKey(String environment) {
        Map<String, Integer> out = new LinkedHashMap<>();
        List<Object> args = new ArrayList<>();
        String sql = "SELECT service_id, version, environment, count(*) n FROM violations "
                + "WHERE status IN ('OPEN','ACKNOWLEDGED','WAIVED')";
        if (environment != null && !environment.isBlank()) {
            sql += " AND environment = ?";
            args.add(environment);
        }
        sql += " GROUP BY service_id, version, environment";
        jdbc.query(sql, rs -> {
            out.put(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3), rs.getInt(4));
        }, args.toArray());
        return out;
    }

    /** W5 validation view: open blocking rule ids for one key (WARN-severity findings excluded). */
    public List<String> openBlockingRuleIds(ServiceKey key) {
        return jdbc.queryForList("""
                SELECT rule_id FROM violations
                WHERE service_id = ? AND version = ? AND environment = ?
                  AND status IN ('OPEN','ACKNOWLEDGED')
                  AND COALESCE(detail->>'severity', 'BLOCK') <> 'WARN'
                ORDER BY rule_id
                """, String.class, key.serviceId(), key.version(), key.environment());
    }
}
