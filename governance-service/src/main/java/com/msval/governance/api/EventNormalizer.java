package com.msval.governance.api;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.persist.Jsonb;

/**
 * DD-005 — Java mirror of msval/core/normalize/event.py for IF-012 (vector-pinned mapping):
 * deployment events carry a full approved_cdm; status reports wrap live_state into a minimal
 * comparable CDM. K8s YAML is Python-only — the caller 422s on non-JSON documents.
 */
public final class EventNormalizer {

    public record Normalized(JsonNode doc, List<String> errors) {
    }

    private EventNormalizer() {
    }

    public static Normalized normalize(JsonNode payload) {
        String kind = payload.path("kind").asText(null);
        if ("deployment".equals(kind)) {
            JsonNode doc = payload.get("approved_cdm");
            if (doc == null || !doc.isObject()) {
                return new Normalized(null,
                        List.of("deployment event without approved_cdm (A-3 fallback: baseline unavailable)"));
            }
            return new Normalized(doc, validateCdm(doc));
        }
        if ("status_report".equals(kind)) {
            JsonNode live = payload.hasNonNull("live_state") ? payload.get("live_state")
                    : Jsonb.MAPPER.createObjectNode();
            ObjectNode doc = Jsonb.MAPPER.createObjectNode();
            doc.put("cdm_version", "1.0");
            ObjectNode service = doc.putObject("service");
            String sid = payload.path("service_id").asText("");
            service.put("id", sid);
            service.put("name", sid);
            service.put("layer", live.path("layer").asText("Domain"));
            doc.putObject("version").put("tag", payload.path("service_version").asText("unknown"));
            doc.put("environment", payload.path("environment").asText(""));
            ObjectNode workload = doc.putObject("workload");
            workload.put("replicas", live.path("replicas").asInt(1));
            JsonNode containers = live.get("containers");
            if (containers != null && containers.isArray() && containers.size() > 0) {
                workload.set("containers", containers);
            } else {
                ObjectNode c = workload.putArray("containers").addObject();
                c.put("name", "unknown");
                ObjectNode image = c.putObject("image");
                image.put("repository", "");
                image.put("registry", "");
                image.put("ref", "");
                image.put("pinned", false);
            }
            ObjectNode provenance = doc.putObject("provenance");
            provenance.put("source_format", "event-json");
            provenance.putArray("source_refs").add(payload.path("event_id").asText(""));
            provenance.put("normalizer_version", "1.0");
            provenance.putArray("unmapped_paths");
            return new Normalized(doc, validateCdm(doc));
        }
        return new Normalized(null, List.of("unknown event kind '" + kind + "'"));
    }

    /** F1 invariants subset (mirrors cdm.validate closely enough for 422 semantics). */
    public static List<String> validateCdm(JsonNode doc) {
        List<String> errors = new ArrayList<>();
        if (!doc.isObject()) {
            errors.add("/: document must be a JSON object");
            return errors;
        }
        if (doc.path("cdm_version").asText("").isEmpty()) {
            errors.add("/cdm_version: required");
        }
        if (doc.path("service").path("id").asText("").isEmpty()) {
            errors.add("/service/id: required");
        }
        if (doc.path("version").path("tag").asText("").isEmpty()) {
            errors.add("/version/tag: required");
        }
        if (doc.path("environment").asText("").isEmpty()) {
            errors.add("/environment: required");
        }
        JsonNode containers = doc.path("workload").path("containers");
        if (!containers.isArray() || containers.size() == 0) {
            errors.add("/workload/containers: must be a non-empty list");
        }
        JsonNode connections = doc.path("topology").path("connections");
        if (connections.isArray()) {
            int i = 0;
            for (JsonNode c : connections) {
                if (c.path("to").asText("").isEmpty()) {
                    errors.add("/topology/connections/" + i + "/to: must be non-empty");
                }
                i++;
            }
        }
        return errors;
    }
}
