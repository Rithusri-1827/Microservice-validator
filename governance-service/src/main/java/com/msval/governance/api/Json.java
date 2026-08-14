package com.msval.governance.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.engine.CheckResult;
import com.msval.governance.engine.Decision;
import com.msval.governance.persist.EvaluationRepo;
import com.msval.governance.persist.Jsonb;
import com.msval.governance.persist.ViolationRepo;

/** W5 response shapes — snake_case wire mapping for the core records (mirrors the Python dataclasses). */
final class Json {

    private Json() {
    }

    static ObjectNode checkResult(CheckResult r) {
        ObjectNode n = Jsonb.MAPPER.createObjectNode();
        n.put("rule_id", r.ruleId());
        n.put("path", r.path());
        n.put("passed", r.passed());
        n.put("reason_code", r.reasonCode());
        n.put("detail", r.detail());
        if (r.observed() != null) {
            n.set("observed", r.observed());
        }
        return n;
    }

    static ObjectNode decision(Decision d) {
        ObjectNode n = Jsonb.MAPPER.createObjectNode();
        n.put("verdict", d.verdict());
        ArrayNode blocking = n.putArray("blocking");
        d.blocking().forEach(r -> blocking.add(checkResult(r)));
        ArrayNode warnings = n.putArray("warnings");
        d.warnings().forEach(r -> warnings.add(checkResult(r)));
        ArrayNode waived = n.putArray("waived");
        for (Decision.Waived w : d.waived()) {
            ObjectNode entry = checkResult(w.result());
            entry.put("waiver_id", w.waiverId());
            waived.add(entry);
        }
        ArrayNode evaluated = n.putArray("evaluated_rules");
        d.evaluatedRules().forEach(evaluated::add);
        n.put("evaluated_under", d.evaluatedUnder());
        n.put("cdm_version", d.cdmVersion());
        n.put("duration_ms", d.durationMs());
        return n;
    }

    static ObjectNode violation(ViolationRepo.ViolationRow v) {
        ObjectNode n = Jsonb.MAPPER.createObjectNode();
        n.put("id", v.id());
        n.put("rule_id", v.ruleId());
        n.put("service_id", v.serviceId());
        n.put("version", v.version());
        n.put("environment", v.environment());
        n.put("status", v.status());
        n.put("first_seen", v.firstSeen().toString());
        n.put("last_seen", v.lastSeen().toString());
        n.put("occurrences", v.occurrences());
        if (v.detail() != null) {
            n.set("detail", v.detail());
        }
        if (v.waiverId() != null) {
            n.put("waiver_id", v.waiverId());
        }
        return n;
    }

    static ObjectNode evaluation(EvaluationRepo.EvaluationRow e) {
        ObjectNode n = Jsonb.MAPPER.createObjectNode();
        n.put("event_id", e.eventId());
        n.put("service_id", e.serviceId());
        n.put("version", e.version());
        n.put("environment", e.environment());
        n.put("phase", e.phase());
        n.put("bundle_version", e.bundleVersion());
        n.put("verdict", e.verdict());
        if (e.durationMs() != null) {
            n.put("duration_ms", e.durationMs());
        }
        n.put("at", e.at().toString());
        return n;
    }

    static ObjectNode object() {
        return Jsonb.MAPPER.createObjectNode();
    }

    static JsonNode text(String s) {
        return Jsonb.MAPPER.getNodeFactory().textNode(s);
    }
}
