package com.msval.governance.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.findings.FindingsService;
import com.msval.governance.persist.ViolationRepo;
import com.msval.governance.support.HttpError;

/** DD-016 / W5 — findings board + lifecycle actions (IF-011). Thin; transitions live in findings. */
@RestController
@RequestMapping("/api/v1/violations")
public class ViolationController {

    private final ViolationRepo violations;
    private final FindingsService findings;

    public ViolationController(ViolationRepo violations, FindingsService findings) {
        this.violations = violations;
        this.findings = findings;
    }

    @GetMapping
    public ObjectNode list(@RequestParam(required = false) String service,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String rule,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        ObjectNode out = Json.object();
        ArrayNode items = out.putArray("items");
        violations.board(blank(service), blank(env), blank(status), blank(rule), page, size)
                .forEach(v -> items.add(Json.violation(v)));
        out.put("total", violations.boardTotal(blank(service), blank(env), blank(status), blank(rule)));
        return out;
    }

    @PostMapping("/{id}/status")
    public ObjectNode setStatus(@PathVariable long id, @RequestBody JsonNode body) {
        String to = body.path("to").asText("");
        return Json.violation(findings.setStatus(id, to));
    }

    @PostMapping("/{id}/waive")
    public ObjectNode waive(@PathVariable long id, @RequestBody JsonNode body) {
        String approvedBy = body.path("approved_by").asText("");
        String reason = body.path("reason").asText("");
        if (approvedBy.isEmpty()) {
            throw HttpError.unprocessable("approved_by is required", List.of());
        }
        Instant expiresAt = parseInstant(body.path("expires_at").asText(""));
        long waiverId = findings.waive(id, expiresAt, approvedBy, reason);
        ObjectNode out = Json.object();
        out.put("waiver_id", waiverId);
        return out;
    }

    private static Instant parseInstant(String s) {
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(s);
            } catch (Exception e2) {
                throw HttpError.unprocessable("expires_at must be an ISO-8601 timestamp", List.of());
            }
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
