package com.msval.governance.persist;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** DD-017 — evaluations (DAT-007, append-only). */
@Repository
public class EvaluationRepo {

    public record EvaluationRow(String eventId, String serviceId, String version, String environment,
            String phase, String bundleVersion, String verdict, Integer durationMs, Instant at) {
    }

    private static final RowMapper<EvaluationRow> ROW = (rs, i) -> new EvaluationRow(
            rs.getString("event_id"), rs.getString("service_id"), rs.getString("version"),
            rs.getString("environment"), rs.getString("phase"), rs.getString("bundle_version"),
            rs.getString("verdict"), (Integer) rs.getObject("duration_ms"),
            rs.getTimestamp("at").toInstant());

    private final JdbcTemplate jdbc;

    public EvaluationRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Append one evaluation. ON CONFLICT DO NOTHING: the event_id PK is the idempotency key —
     * an IF-007 retry beyond the gateway dedup window must not blow up Txn A (A-2 semantics).
     */
    public void insert(String eventId, ServiceKey key, String phase, String bundleVersion,
            String verdict, Integer durationMs) {
        jdbc.update("""
                INSERT INTO evaluations(event_id, service_id, version, environment, phase,
                                        bundle_version, verdict, duration_ms)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (event_id) DO NOTHING
                """, eventId, key.serviceId(), key.version(), key.environment(), phase,
                bundleVersion, verdict, durationMs);
    }

    /** Latest runtime evaluation for a key — waiver-expiry "last eval" check + W5 validation view. */
    public Optional<EvaluationRow> latestRuntime(ServiceKey key) {
        return jdbc.query("SELECT * FROM evaluations WHERE service_id = ? AND version = ? "
                        + "AND environment = ? AND phase = 'runtime' ORDER BY at DESC LIMIT 1", ROW,
                key.serviceId(), key.version(), key.environment()).stream().findFirst();
    }

    /** W5 GET /evaluations — filtered page (index ev_service). */
    public List<EvaluationRow> page(String serviceId, String environment, int page, int size) {
        return jdbc.query("""
                SELECT * FROM evaluations
                WHERE (?::text IS NULL OR service_id = ?) AND (?::text IS NULL OR environment = ?)
                ORDER BY at DESC LIMIT ? OFFSET ?
                """, ROW, serviceId, serviceId, environment, environment, size, page * size);
    }

    /** Timestamp helper for tests seeding history. */
    public static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
