package com.msval.governance.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.persist.EvaluationRepo;
import com.msval.governance.persist.LifecycleRepo;
import com.msval.governance.persist.ServiceKey;
import com.msval.governance.persist.ViolationRepo;
import com.msval.governance.support.HttpError;

/** DD-016 / W5 — service tree, evaluation history, and the CI promotion-gate query (IF-011). */
@RestController
@RequestMapping("/api/v1")
public class ServiceController {

    private final LifecycleRepo lifecycle;
    private final ViolationRepo violations;
    private final EvaluationRepo evaluations;

    public ServiceController(LifecycleRepo lifecycle, ViolationRepo violations,
            EvaluationRepo evaluations) {
        this.lifecycle = lifecycle;
        this.violations = violations;
        this.evaluations = evaluations;
    }

    @GetMapping("/services/tree")
    public ObjectNode tree(@RequestParam(required = false) String env) {
        Map<String, Integer> open = violations.openCountsByKey(env);
        Map<String, ArrayNode> byService = new LinkedHashMap<>();
        ObjectNode out = Json.object();
        ArrayNode services = out.putArray("services");
        for (LifecycleRepo.LifecycleRow row : lifecycle.treeRows(env)) {
            ArrayNode versions = byService.get(row.serviceId());
            if (versions == null) {
                ObjectNode svc = services.addObject();
                svc.put("service", row.serviceId());
                versions = svc.putArray("versions");
                byService.put(row.serviceId(), versions);
            }
            ObjectNode v = versions.addObject();
            v.put("version", row.version());
            v.put("environment", row.environment());
            v.put("state", row.state());
            v.put("stale", row.stale());
            v.put("open_findings", open.getOrDefault(
                    row.serviceId() + "|" + row.version() + "|" + row.environment(), 0));
        }
        return out;
    }

    @GetMapping("/evaluations")
    public ObjectNode evaluations(@RequestParam(required = false) String service,
            @RequestParam(required = false) String env,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        ObjectNode out = Json.object();
        ArrayNode items = out.putArray("items");
        evaluations.page(blank(service), blank(env), page, size)
                .forEach(e -> items.add(Json.evaluation(e)));
        return out;
    }

    /** IF-011 CI promotion gate: did this version pass validation in the prerequisite env? */
    @GetMapping("/services/{id}/versions/{version}/validation")
    public ObjectNode validation(@PathVariable String id, @PathVariable String version,
            @RequestParam String env) {
        ServiceKey key = new ServiceKey(id, version, env);
        LifecycleRepo.LifecycleRow row = lifecycle.find(key)
                .orElseThrow(() -> HttpError.notFound(
                        "no lifecycle record for " + id + ":" + version + " in " + env));
        ObjectNode out = Json.object();
        out.put("validated", "Validated".equals(row.state()));
        out.put("state", row.state());
        var latest = evaluations.latestRuntime(key);
        out.put("evaluated_under", latest.map(EvaluationRepo.EvaluationRow::bundleVersion).orElse(null));
        out.put("at", latest.map(e -> e.at().toString()).orElse(null));
        ArrayNode blocking = out.putArray("open_blocking");
        violations.openBlockingRuleIds(key).forEach(blocking::add);
        return out;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
