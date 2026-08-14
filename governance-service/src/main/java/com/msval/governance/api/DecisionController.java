package com.msval.governance.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.engine.Decision;
import com.msval.governance.engine.Engine;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.persist.EnvironmentRepo;
import com.msval.governance.registry.RuleCache;
import com.msval.governance.registry.RuleSet;
import com.msval.governance.support.HttpError;

/**
 * DD-016 / W5 / IF-012 — decision-on-demand: sync, stateless, does NOT persist (dry-run).
 * format=cdm takes the document as-is; format=event-json runs the vector-pinned
 * EventNormalizer; raw K8s YAML is Python-only ⇒ 422 (IF-012 rev 3).
 */
@RestController
@RequestMapping("/api/v1/decisions")
public class DecisionController {

    private final Engine engine;
    private final RuleCache ruleCache;
    private final EnvironmentRepo environments;

    public DecisionController(Engine engine, RuleCache ruleCache, EnvironmentRepo environments) {
        this.engine = engine;
        this.ruleCache = ruleCache;
        this.environments = environments;
    }

    @PostMapping
    public ObjectNode decide(@RequestBody JsonNode body) {
        JsonNode document = body.get("document");
        String format = body.path("format").asText("cdm");
        String environment = body.path("environment").asText("");
        String phase = body.path("phase").asText("runtime");

        if (document == null || document.isNull()) {
            throw HttpError.unprocessable("document is required", List.of());
        }
        if (document.isTextual()) {
            throw HttpError.unprocessable("raw text documents (K8s YAML) are not accepted here — "
                    + "normalize with the Python CLI first (IF-012 rev 3)", List.of());
        }
        if (environment.isEmpty()) {
            throw HttpError.unprocessable("environment is required", List.of());
        }
        if (!List.of("ci", "intake", "runtime").contains(phase)) {
            throw HttpError.unprocessable("phase must be ci|intake|runtime", List.of());
        }

        JsonNode doc;
        if ("event-json".equals(format)) {
            EventNormalizer.Normalized n = EventNormalizer.normalize(document);
            if (n.doc() == null || !n.errors().isEmpty()) {
                throw HttpError.unprocessable("event normalization failed", n.errors());
            }
            doc = n.doc();
        } else if ("cdm".equals(format)) {
            List<String> errors = EventNormalizer.validateCdm(document);
            if (!errors.isEmpty()) {
                throw HttpError.unprocessable("invalid CDM document", errors);
            }
            doc = document;
        } else {
            throw HttpError.unprocessable("format must be cdm|event-json", List.of());
        }

        Map<String, Long> capacity = null;
        var env = environments.find(environment);
        if (env.isPresent() && env.get().capacity() != null) {
            capacity = new LinkedHashMap<>();
            var it = env.get().capacity().fields();
            while (it.hasNext()) {
                var e = it.next();
                capacity.put(e.getKey(), e.getValue().asLong());
            }
        }
        RuleSet rules = ruleCache.ruleSetFor(phase);
        EvalContext ctx = new EvalContext(phase, environment, capacity, null, null, null, null,
                List.of(), Instant.now().toString());
        long t0 = System.nanoTime();
        Decision d = engine.decide(doc, rules.rules(), ctx,
                rules.version() == null ? "none" : rules.version());
        int durationMs = (int) ((System.nanoTime() - t0) / 1_000_000);
        Decision timed = new Decision(d.verdict(), d.blocking(), d.warnings(), d.waived(),
                d.evaluatedRules(), d.evaluatedUnder(), d.cdmVersion(), durationMs);
        return Json.decision(timed);
    }
}
