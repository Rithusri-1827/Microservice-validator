package com.msval.governance.persist;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** DD-017 — publish_audit (DAT-010, append-only). */
@Repository
public class AuditRepo {

    private final JdbcTemplate jdbc;

    public AuditRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String bundleVersion, String action, String actor) {
        jdbc.update("INSERT INTO publish_audit(bundle_version, action, actor) VALUES (?,?,?)",
                bundleVersion, action, actor);
    }
}
