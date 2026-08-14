package com.msval.governance.persist;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.msval.governance.engine.Waiver;

/** DD-017 — waivers (DAT-009). Engine-facing rows use the IF-001 {@link Waiver} shape. */
@Repository
public class WaiverRepo {

    public record WaiverRow(long id, String ruleId, String serviceId, String version,
            String environment, String approvedBy, String reason, Instant expiresAt, String status) {
    }

    private static final RowMapper<WaiverRow> ROW = (rs, i) -> new WaiverRow(
            rs.getLong("id"), rs.getString("rule_id"), rs.getString("service_id"),
            rs.getString("version"), rs.getString("environment"), rs.getString("approved_by"),
            rs.getString("reason"), rs.getTimestamp("expires_at").toInstant(), rs.getString("status"));

    private final JdbcTemplate jdbc;

    public WaiverRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String ruleId, ServiceKey key, String approvedBy, String reason, Instant expiresAt) {
        Long id = jdbc.queryForObject("""
                INSERT INTO waivers(rule_id, service_id, version, environment, approved_by, reason, expires_at)
                VALUES (?,?,?,?,?,?,?) RETURNING id
                """, Long.class, ruleId, key.serviceId(), key.version(), key.environment(),
                approvedBy, reason, Timestamp.from(expiresAt));
        return id == null ? 0 : id;
    }

    /** IF-014 activeWaivers backing query (cached 5 s by findings). ISO expiry for the engine. */
    public List<Waiver> activeFor(ServiceKey key) {
        return jdbc.query("""
                SELECT id, rule_id, service_id, version, environment, expires_at FROM waivers
                WHERE service_id = ? AND version = ? AND environment = ?
                  AND status = 'active' AND expires_at > now()
                """, (rs, i) -> new Waiver(rs.getLong("id"), rs.getString("rule_id"),
                        rs.getString("service_id"), rs.getString("version"), rs.getString("environment"),
                        rs.getTimestamp("expires_at").toInstant().toString()),
                key.serviceId(), key.version(), key.environment());
    }

    /** DD-015 waiver sweep: claim due waivers (SKIP LOCKED) and mark them expired atomically. */
    public List<WaiverRow> expireDue() {
        return jdbc.query("""
                UPDATE waivers SET status = 'expired'
                WHERE id IN (SELECT id FROM waivers WHERE status = 'active' AND expires_at <= now()
                             FOR UPDATE SKIP LOCKED)
                RETURNING *
                """, ROW);
    }
}
