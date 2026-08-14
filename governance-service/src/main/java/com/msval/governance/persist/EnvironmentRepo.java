package com.msval.governance.persist;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;

/** DD-017 — environments (DAT-003). */
@Repository
public class EnvironmentRepo {

    public record EnvironmentRow(String name, JsonNode capacity, int settleDelayS, Integer orderIndex) {
    }

    private static final RowMapper<EnvironmentRow> ROW = (rs, i) -> new EnvironmentRow(
            rs.getString("name"), Jsonb.read(rs.getString("capacity")),
            rs.getInt("settle_delay_s"), (Integer) rs.getObject("order_index"));

    private final JdbcTemplate jdbc;

    public EnvironmentRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EnvironmentRow> find(String name) {
        List<EnvironmentRow> r = jdbc.query(
                "SELECT name, capacity, settle_delay_s, order_index FROM environments WHERE name = ?", ROW, name);
        return r.stream().findFirst();
    }

    public List<EnvironmentRow> all() {
        return jdbc.query("SELECT name, capacity, settle_delay_s, order_index FROM environments ORDER BY name", ROW);
    }

    /** FLOW-010 admin upsert: settle/order keep their previous value when null (partial update). */
    public void upsert(String name, JsonNode capacity, Integer settleDelayS, Integer orderIndex) {
        jdbc.update("""
                INSERT INTO environments(name, capacity, settle_delay_s, order_index)
                VALUES (?, ?::jsonb, COALESCE(?, 120), ?)
                ON CONFLICT (name) DO UPDATE SET
                  capacity = EXCLUDED.capacity,
                  settle_delay_s = COALESCE(?, environments.settle_delay_s),
                  order_index = COALESCE(?, environments.order_index)
                """, name, Jsonb.write(capacity), settleDelayS, orderIndex, settleDelayS, orderIndex);
    }
}
