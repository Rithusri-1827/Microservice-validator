package com.msval.governance.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.persist.EnvironmentRepo;
import com.msval.governance.support.HttpError;

/**
 * DD-016 / W5 / IF-011 — environment admin (FLOW-010). order_index is informational
 * only; the authoritative promotion order lives in LCY rule params (rev 9).
 */
@RestController
@RequestMapping("/api/v1/environments")
public class EnvironmentController {

    private final EnvironmentRepo environments;

    public EnvironmentController(EnvironmentRepo environments) {
        this.environments = environments;
    }


    /** GET one environment (capacity feeds the intake stage's symbolic solving — TASK-028 fix). */
    @GetMapping("/{name}")
    public ObjectNode get(@PathVariable String name) {
        var row = environments.find(name)
                .orElseThrow(() -> new com.msval.governance.support.HttpError(404, "NOT_FOUND",
                        "unknown environment '" + name + "'", null));
        ObjectNode out = com.msval.governance.persist.Jsonb.MAPPER.createObjectNode();
        out.put("name", row.name());
        out.set("capacity", row.capacity());
        out.put("settle_delay_s", row.settleDelayS());
        if (row.orderIndex() != null) out.put("order_index", row.orderIndex());
        return out;
    }

    @PutMapping("/{name}")
    public ObjectNode upsert(@PathVariable String name, @RequestBody JsonNode body) {
        JsonNode capacity = body.get("capacity");
        List<String> errors = new ArrayList<>();
        if (capacity == null || !capacity.isObject()) {
            errors.add("capacity must be an object {cpu, memory}");
        } else {
            if (!capacity.path("cpu").canConvertToLong()) {
                errors.add("capacity.cpu must be an integer (millicores, F3 canonical)");
            }
            if (!capacity.path("memory").canConvertToLong()) {
                errors.add("capacity.memory must be an integer (bytes, F3 canonical)");
            }
        }
        Integer settle = body.hasNonNull("settle_delay_s") ? body.get("settle_delay_s").asInt() : null;
        if (settle != null && settle < 0) {
            errors.add("settle_delay_s must be >= 0");
        }
        Integer orderIndex = body.hasNonNull("order_index") ? body.get("order_index").asInt() : null;
        if (!errors.isEmpty()) {
            throw HttpError.unprocessable("invalid environment", errors);
        }
        environments.upsert(name, capacity, settle, orderIndex);
        var row = environments.find(name).orElseThrow();
        ObjectNode out = Json.object();
        out.put("name", row.name());
        out.set("capacity", row.capacity());
        out.put("settle_delay_s", row.settleDelayS());
        if (row.orderIndex() != null) {
            out.put("order_index", row.orderIndex());
        }
        return out;
    }
}
