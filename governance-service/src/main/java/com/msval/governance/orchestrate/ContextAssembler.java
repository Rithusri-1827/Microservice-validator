package com.msval.governance.orchestrate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.engine.CheckResult;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.Waiver;
import com.msval.governance.findings.FindingsService;
import com.msval.governance.persist.EnvironmentRepo;
import com.msval.governance.persist.Jsonb;
import com.msval.governance.persist.LifecycleRepo;
import com.msval.governance.persist.ServiceKey;
import com.msval.governance.persist.ServiceRepo;
import com.msval.governance.persist.TopologyRepo;

/**
 * DD-013 — ContextAssembler: env row, live snapshot, declared baseline, latest topology
 * (captured + declared), waivers (IF-014), promotion history — plus the D4-4 drift lane
 * producing synthetic CheckResults outside the engine.
 */
@Component
public class ContextAssembler {

    /** Everything validate(key) needs for one evaluation. */
    public record Assembled(JsonNode doc, EvalContext ctx, List<CheckResult> driftResults,
            List<String> driftEvaluated) {
    }

    private final EnvironmentRepo environments;
    private final ServiceRepo services;
    private final LifecycleRepo lifecycle;
    private final TopologyRepo topology;
    private final FindingsService findings;

    public ContextAssembler(EnvironmentRepo environments, ServiceRepo services,
            LifecycleRepo lifecycle, TopologyRepo topology, FindingsService findings) {
        this.environments = environments;
        this.services = services;
        this.lifecycle = lifecycle;
        this.topology = topology;
        this.findings = findings;
    }

    /** Null when there is nothing to evaluate (no declared baseline and no live snapshot). */
    public Assembled build(ServiceKey key) {
        JsonNode declared = services.declaredCdm(key.serviceId(), key.version()).orElse(null);
        LifecycleRepo.LifecycleRow row = lifecycle.find(key).orElse(null);
        JsonNode live = row == null ? null : row.liveSnapshot();
        JsonNode doc = declared != null ? DriftComputer.mergeDoc(declared, live)
                : wrapLiveOnly(key, live);
        if (doc == null) {
            return null;
        }

        Map<String, Long> capacity = null;
        var env = environments.find(key.environment());
        if (env.isPresent() && env.get().capacity() != null) {
            capacity = new LinkedHashMap<>();
            var it = env.get().capacity().fields();
            while (it.hasNext()) {
                var e = it.next();
                capacity.put(e.getKey(), e.getValue().asLong());
            }
        }

        List<JsonNode> promotionHistory = new ArrayList<>();
        for (LifecycleRepo.LifecycleRow h : lifecycle.historyOf(key.serviceId(), key.version())) {
            ObjectNode entry = Jsonb.MAPPER.createObjectNode();
            entry.put("env", h.environment());
            entry.put("state", h.state());
            promotionHistory.add(entry);
        }

        JsonNode captured = topology.latest(key.environment(), "captured").orElse(null);
        JsonNode declaredGraph = topology.latest(key.environment(), "declared").orElse(null);
        ObjectNode topo = Jsonb.MAPPER.createObjectNode();
        if (captured != null) {
            topo.set("captured", captured);
        }
        if (declaredGraph != null) {
            topo.set("declared", declaredGraph);
        }

        List<Waiver> waivers = findings.activeWaivers(key);
        EvalContext ctx = new EvalContext("runtime", key.environment(), capacity,
                promotionHistory, live, declared, topo.isEmpty() ? null : topo,
                waivers, Instant.now().toString());

        // D4-4 drift lane — synthetic results, engine stays pure. A drift rule id enters
        // the evaluated set whenever its comparison actually ran (enables auto-resolve).
        List<CheckResult> drift = new ArrayList<>();
        List<String> driftEvaluated = new ArrayList<>();
        if (declared != null && live != null) {
            driftEvaluated.add(DriftComputer.CONFIG_RULE);
            CheckResult c = DriftComputer.configDrift(declared, live);
            if (c != null) {
                drift.add(c);
            }
        }
        if (captured != null && declaredGraph != null) {
            driftEvaluated.add(DriftComputer.TOPOLOGY_RULE);
            CheckResult t = DriftComputer.topologyDrift(captured, declaredGraph);
            if (t != null) {
                drift.add(t);
            }
        }
        return new Assembled(doc, ctx, drift, driftEvaluated);
    }

    /** A-3 fallback: no declared baseline — wrap the live snapshot as a minimal CDM doc. */
    private static JsonNode wrapLiveOnly(ServiceKey key, JsonNode live) {
        if (live == null) {
            return null;
        }
        ObjectNode doc = Jsonb.MAPPER.createObjectNode();
        doc.put("cdm_version", "1.0");
        ObjectNode service = doc.putObject("service");
        service.put("id", key.serviceId());
        service.put("name", key.serviceId());
        service.put("layer", live.path("layer").asText("Domain"));
        doc.putObject("version").put("tag", key.version());
        doc.put("environment", key.environment());
        ObjectNode workload = doc.putObject("workload");
        workload.put("replicas", live.path("replicas").asInt(1));
        workload.set("containers", live.path("containers").isArray()
                ? live.get("containers") : Jsonb.MAPPER.createArrayNode());
        ObjectNode provenance = doc.putObject("provenance");
        provenance.put("source_format", "event-json");
        provenance.putArray("source_refs");
        provenance.put("normalizer_version", "1.0");
        provenance.putArray("unmapped_paths");
        return doc;
    }
}
