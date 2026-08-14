package com.msval.governance.persist;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;

/** DD-017 — services + service_versions (DAT-004). Declared CDM is the CI-approved baseline. */
@Repository
public class ServiceRepo {

    private final JdbcTemplate jdbc;

    public ServiceRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Txn A step 1: INSERT … ON CONFLICT DO NOTHING. */
    public void upsertService(String serviceId, String name, String layer, String team) {
        jdbc.update("INSERT INTO services(service_id, name, layer, team) VALUES (?,?,?,?) "
                + "ON CONFLICT (service_id) DO NOTHING", serviceId, name, layer, team);
    }

    /** Txn A step 2: declared baseline rides the deployment event; re-deploy refreshes it. */
    public void upsertVersion(String serviceId, String version, String imageDigest, JsonNode declaredCdm) {
        jdbc.update("""
                INSERT INTO service_versions(service_id, version, image_digest, declared_cdm)
                VALUES (?,?,?,?::jsonb)
                ON CONFLICT (service_id, version) DO UPDATE SET
                  declared_cdm = COALESCE(EXCLUDED.declared_cdm, service_versions.declared_cdm),
                  image_digest = COALESCE(EXCLUDED.image_digest, service_versions.image_digest)
                """, serviceId, version, imageDigest, Jsonb.write(declaredCdm));
    }

    public Optional<JsonNode> declaredCdm(String serviceId, String version) {
        List<String> r = jdbc.queryForList(
                "SELECT declared_cdm FROM service_versions WHERE service_id = ? AND version = ?",
                String.class, serviceId, version);
        return r.stream().findFirst().map(Jsonb::read);
    }
}
