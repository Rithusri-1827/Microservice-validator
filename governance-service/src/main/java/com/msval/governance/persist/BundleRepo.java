package com.msval.governance.persist;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;

/** DD-017 — rule_bundles + policy_rules + active_bundle (DAT-001/002 + the active pointer). */
@Repository
public class BundleRepo {

    public record RuleRow(String ruleId, JsonNode definition) {
    }

    private final JdbcTemplate jdbc;

    public BundleRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> versionByHash(String contentHash) {
        return jdbc.queryForList("SELECT version FROM rule_bundles WHERE content_hash = ?",
                String.class, contentHash).stream().findFirst();
    }

    public boolean exists(String version) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM rule_bundles WHERE version = ?",
                Integer.class, version);
        return n != null && n > 0;
    }

    public Optional<JsonNode> manifest(String version) {
        return jdbc.queryForList("SELECT manifest FROM rule_bundles WHERE version = ?",
                String.class, version).stream().findFirst().map(Jsonb::read);
    }

    public void insertBundle(String version, String gitCommit, String contentHash,
            String grammarVersion, JsonNode manifest, String publishedBy) {
        jdbc.update("""
                INSERT INTO rule_bundles(version, git_commit, content_hash, grammar_version, manifest, published_by)
                VALUES (?,?,?,?,?::jsonb,?)
                """, version, gitCommit, contentHash, grammarVersion, Jsonb.write(manifest), publishedBy);
    }

    public void insertRule(String bundleVersion, String ruleId, String family, String severity,
            List<String> phases, List<String> environments, JsonNode definition) {
        jdbc.update("""
                INSERT INTO policy_rules(bundle_version, rule_id, family, severity, phases, environments, definition)
                VALUES (?,?,?,?,?::text[],?::text[],?::jsonb)
                """, bundleVersion, ruleId, family, severity, Jsonb.textArray(phases),
                Jsonb.textArray(environments), Jsonb.write(definition));
    }

    /** Frozen rule docs of one bundle; stage routing is recomputed by the cache (Capabilities). */
    public List<RuleRow> rulesFor(String bundleVersion) {
        return jdbc.query("SELECT rule_id, definition FROM policy_rules WHERE bundle_version = ? "
                        + "ORDER BY rule_id",
                (rs, i) -> new RuleRow(rs.getString(1), Jsonb.read(rs.getString(2))), bundleVersion);
    }

    /** W5 GET /bundles/version — reads active_bundle directly (DD-012: cheap). */
    public Map<String, String> activeVersions() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT stage, version FROM active_bundle ORDER BY stage",
                rs -> {
                    out.put(rs.getString(1), rs.getString(2));
                });
        return out;
    }

    /** DD-012 activate txn: flip the per-stage active pointer. */
    public void setActive(String stage, String version) {
        jdbc.update("""
                INSERT INTO active_bundle(stage, version) VALUES (?,?)
                ON CONFLICT (stage) DO UPDATE SET version = EXCLUDED.version
                """, stage, version);
    }
}
