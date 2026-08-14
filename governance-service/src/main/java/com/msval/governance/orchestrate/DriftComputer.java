package com.msval.governance.orchestrate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.engine.CheckResult;
import com.msval.governance.persist.Jsonb;

/**
 * D4-4 — drift is computed OUTSIDE the engine (ContextAssembler lane): declared-vs-live
 * canonical-JSON diff over containers[*].{image,security,resources,probes,env_refs} ⇒
 * DRIFT-CONFIG/CONFIG_DRIFT; captured-vs-declared edge-set diff ⇒ DRIFT-TOPOLOGY/TOPOLOGY_DRIFT.
 * Severity is applied by the committer: BLOCK in production, WARN elsewhere (constants in code).
 */
public final class DriftComputer {

    public static final String CONFIG_RULE = "DRIFT-CONFIG";
    public static final String TOPOLOGY_RULE = "DRIFT-TOPOLOGY";
    private static final List<String> DRIFT_FIELDS =
            List.of("image", "security", "resources", "probes", "env_refs");

    private DriftComputer() {
    }

    /** Failed CheckResult when the declared baseline and the live snapshot diverge; null when clean. */
    public static CheckResult configDrift(JsonNode declaredCdm, JsonNode liveState) {
        if (declaredCdm == null || liveState == null) {
            return null;
        }
        JsonNode declared = declaredCdm.path("workload").path("containers");
        JsonNode live = liveState.path("containers");
        if (!declared.isArray() || !live.isArray()) {
            return null;
        }
        Map<String, JsonNode> liveByName = byName(live);
        List<String> diffs = new ArrayList<>();
        int i = 0;
        Set<String> declaredNames = new LinkedHashSet<>();
        for (JsonNode dc : declared) {
            String name = dc.path("name").asText("");
            declaredNames.add(name);
            JsonNode lc = liveByName.get(name);
            if (lc == null) {
                diffs.add("workload.containers[" + i + "] (missing live container '" + name + "')");
                i++;
                continue;
            }
            for (String field : DRIFT_FIELDS) {
                JsonNode lv = lc.get(field);
                if (lv == null || lv.isNull()) {
                    continue; // partial live reports: only compare what the agent observed
                }
                JsonNode dv = dc.get(field);
                if (dv == null || !canonical(dv).equals(canonical(lv))) {
                    diffs.add("workload.containers[" + i + "]." + field);
                }
            }
            i++;
        }
        for (String liveName : liveByName.keySet()) {
            if (!declaredNames.contains(liveName)) {
                diffs.add("workload.containers (undeclared live container '" + liveName + "')");
            }
        }
        if (diffs.isEmpty()) {
            return null;
        }
        ObjectNode observed = Jsonb.MAPPER.createObjectNode();
        ArrayNode paths = observed.putArray("paths");
        diffs.forEach(paths::add);
        return new CheckResult(CONFIG_RULE, "workload.containers", false, "CONFIG_DRIFT",
                "declared baseline and live state differ at: " + String.join(", ", diffs), observed);
    }

    /** Edge-set diff captured vs declared; null when equal or either snapshot is absent. */
    public static CheckResult topologyDrift(JsonNode capturedGraph, JsonNode declaredGraph) {
        if (capturedGraph == null || declaredGraph == null) {
            return null;
        }
        Set<String> captured = edgeSet(capturedGraph);
        Set<String> declared = edgeSet(declaredGraph);
        List<String> capturedOnly = new ArrayList<>();
        List<String> declaredOnly = new ArrayList<>();
        for (String e : captured) {
            if (!declared.contains(e)) {
                capturedOnly.add(e);
            }
        }
        for (String e : declared) {
            if (!captured.contains(e)) {
                declaredOnly.add(e);
            }
        }
        if (capturedOnly.isEmpty() && declaredOnly.isEmpty()) {
            return null;
        }
        ObjectNode observed = Jsonb.MAPPER.createObjectNode();
        ArrayNode co = observed.putArray("captured_only");
        capturedOnly.forEach(co::add);
        ArrayNode dm = observed.putArray("declared_only");
        declaredOnly.forEach(dm::add);
        return new CheckResult(TOPOLOGY_RULE, "topology.connections", false, "TOPOLOGY_DRIFT",
                "captured/declared edge sets differ — captured-only: " + capturedOnly
                        + ", declared-only: " + declaredOnly, observed);
    }

    /** DD-013 validate(): doc = merge(declared_cdm, live_snapshot) — live overrides per-field. */
    public static JsonNode mergeDoc(JsonNode declaredCdm, JsonNode liveState) {
        if (declaredCdm == null) {
            return null;
        }
        if (liveState == null) {
            return declaredCdm;
        }
        ObjectNode doc = declaredCdm.deepCopy();
        JsonNode liveContainers = liveState.get("containers");
        if (liveContainers != null && liveContainers.isArray()) {
            ObjectNode workload = doc.with("workload");
            JsonNode declared = workload.get("containers");
            ArrayNode merged = Jsonb.MAPPER.createArrayNode();
            Map<String, JsonNode> liveByName = byName(liveContainers);
            Set<String> used = new LinkedHashSet<>();
            if (declared != null && declared.isArray()) {
                for (JsonNode dc : declared) {
                    String name = dc.path("name").asText("");
                    JsonNode lc = liveByName.get(name);
                    if (lc != null) {
                        used.add(name);
                        merged.add(overlay(dc, lc));
                    } else {
                        merged.add(dc);
                    }
                }
            }
            for (Map.Entry<String, JsonNode> e : liveByName.entrySet()) {
                if (!used.contains(e.getKey())) {
                    merged.add(e.getValue());
                }
            }
            workload.set("containers", merged);
        }
        JsonNode jvm = liveState.get("jvm");
        if (jvm != null && !jvm.isNull()) {
            doc.with("workload").set("jvm", jvm);
        }
        JsonNode runtimePolicy = liveState.get("runtime_policy");
        if (runtimePolicy != null && !runtimePolicy.isNull()) {
            doc.set("runtime_policy", runtimePolicy);
        }
        return doc;
    }

    /** Deep merge: objects recurse, live scalars/arrays win. */
    private static JsonNode overlay(JsonNode base, JsonNode over) {
        if (!base.isObject() || !over.isObject()) {
            return over;
        }
        ObjectNode out = base.deepCopy();
        for (Iterator<String> it = over.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            JsonNode ov = over.get(f);
            JsonNode bv = out.get(f);
            if (bv != null && bv.isObject() && ov.isObject()) {
                out.set(f, overlay(bv, ov));
            } else {
                out.set(f, ov);
            }
        }
        return out;
    }

    private static Map<String, JsonNode> byName(JsonNode containers) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode c : containers) {
            out.put(c.path("name").asText(""), c);
        }
        return out;
    }

    private static Set<String> edgeSet(JsonNode graph) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode e : graph.path("edges")) {
            out.add(e.path("from").asText("") + "->" + e.path("to").asText(""));
        }
        return out;
    }

    /** Canonical JSON (sorted keys, compact) for field-level diffing. */
    static String canonical(JsonNode node) {
        if (node.isObject()) {
            StringBuilder sb = new StringBuilder("{");
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            boolean first = true;
            for (String n : names) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(n).append("\":").append(canonical(node.get(n)));
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (JsonNode n : node) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(canonical(n));
            }
            return sb.append(']').toString();
        }
        return node.toString();
    }
}
