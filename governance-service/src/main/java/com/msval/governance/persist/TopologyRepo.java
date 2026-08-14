package com.msval.governance.persist;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;

/** DD-017 — topology_snapshots (DAT-006, append-only). Graph JSON: {nodes:[id], edges:[{from,to,…}], node_attrs?}. */
@Repository
public class TopologyRepo {

    private final JdbcTemplate jdbc;

    public TopologyRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<JsonNode> latest(String environment, String origin) {
        return jdbc.queryForList("SELECT graph FROM topology_snapshots WHERE environment = ? "
                        + "AND origin = ? ORDER BY at DESC LIMIT 1", String.class, environment, origin)
                .stream().findFirst().map(Jsonb::read);
    }

    public long append(String environment, String origin, JsonNode graph) {
        Long id = jdbc.queryForObject("INSERT INTO topology_snapshots(environment, origin, graph) "
                        + "VALUES (?,?,?::jsonb) RETURNING id", Long.class,
                environment, origin, Jsonb.write(graph));
        return id == null ? 0 : id;
    }
}
