package com.msval.governance.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.msval.governance.cdm.PathResolver;
import com.msval.governance.cdm.SelectorException;
import com.msval.governance.cdm.Slice;

/**
 * DD-009 — engine-java: the rule-agnostic interpreter (IF-001). Pure; no I/O, no clocks.
 * Behavioural mirror of msval/core/engine/engine.py; the conformance corpus pins parity.
 * Stateless and thread-safe: one instance is shared across workers.
 */
public final class Engine {

    private static final Comparator<CheckResult> BY_RULE_THEN_PATH =
            Comparator.comparing(CheckResult::ruleId).thenComparing(CheckResult::path);

    private final OperatorRegistry registry;

    public Engine(OperatorRegistry registry) {
        this.registry = registry;
    }

    /** All CheckResults (pass and fail) for one rule against one document. */
    public List<CheckResult> evaluateRule(JsonNode doc, JsonNode rule, EvalContext ctx) {
        String rid = rule.path("id").asText();
        String target = rule.path("target").asText();
        String opName = rule.path("operator").asText();

        Operator op = registry.get(opName);
        if (op == null) {
            return List.of(new CheckResult(rid, target, false, "ENGINE_FAULT:UNKNOWN_OPERATOR", opName));
        }
        List<Slice> slices;
        try {
            slices = PathResolver.resolve(doc, target);
        } catch (SelectorException e) {
            return List.of(new CheckResult(rid, target, false, "ENGINE_FAULT:" + e.code(), e.getMessage()));
        }
        if (slices.isEmpty()) {
            slices = List.of(new Slice(target, PathResolver.MISSING));
        }
        JsonNode params = rule.hasNonNull("params") ? rule.get("params")
                : JsonNodeFactory.instance.objectNode();

        List<CheckResult> out = new ArrayList<>();
        for (Slice s : slices) {
            if (s.value().isMissingNode() && !"exists".equals(opName)) {
                out.add(new CheckResult(rid, s.path(), false, "RULE_FAILED", "path missing"));
                continue;
            }
            OpOutcome o;
            try {
                o = op.evaluate(s.value(), params, ctx);
            } catch (Exception exc) { // operator bug or bad params that escaped publish lint
                out.add(new CheckResult(rid, s.path(), false, "ENGINE_FAULT:BAD_PARAMS",
                        String.valueOf(exc.getMessage())));
                continue;
            }
            out.add(new CheckResult(rid, s.path(), o.passed(), o.reasonCode(), o.detail(),
                    o.passed() ? null : safe(s.value())));
        }
        return out;
    }

    /** DD-006 decide(): phase/env filter → evaluate → waiver match → partition → sorted Decision. */
    public Decision decide(JsonNode doc, List<JsonNode> rules, EvalContext ctx, String bundleVersion) {
        List<CheckResult> blocking = new ArrayList<>();
        List<CheckResult> warnings = new ArrayList<>();
        List<Decision.Waived> waived = new ArrayList<>();
        List<String> evaluated = new ArrayList<>();
        boolean fault = false;

        JsonNode service = doc.path("service");
        String sid = service.isObject() ? service.path("id").asText("") : "";
        JsonNode version = doc.path("version");
        String ver = version.isObject() ? version.path("tag").asText("") : "";

        for (JsonNode rule : rules) {
            if (!applicable(rule, ctx)) {
                continue;
            }
            evaluated.add(rule.path("id").asText());
            for (CheckResult r : evaluateRule(doc, rule, ctx)) {
                if (r.passed()) {
                    continue;
                }
                if (r.reasonCode().startsWith("ENGINE_FAULT")) {
                    fault = true;
                    blocking.add(r);
                    continue;
                }
                Waiver w = ctx.waivers().stream()
                        .filter(x -> x.ruleId().equals(r.ruleId()) && x.serviceId().equals(sid)
                                && x.version().equals(ver) && x.environment().equals(ctx.environment())
                                && x.expiresAt().compareTo(ctx.now()) > 0)
                        .findFirst().orElse(null);
                if (w != null) {
                    waived.add(new Decision.Waived(r, w.waiverId()));
                } else if ("WARN".equals(rule.path("severity").asText())) {
                    warnings.add(r);
                } else {
                    blocking.add(r);
                }
            }
        }

        String verdict = fault ? "ERROR" : (blocking.isEmpty() ? "PASS" : "FAIL");
        blocking.sort(BY_RULE_THEN_PATH);
        warnings.sort(BY_RULE_THEN_PATH);
        waived.sort(Comparator.comparing((Decision.Waived t) -> t.result().ruleId())
                .thenComparing(t -> t.result().path()));
        evaluated.sort(Comparator.naturalOrder());
        JsonNode cdmVersion = doc.path("cdm_version");
        String cdm = cdmVersion.isMissingNode() || cdmVersion.isNull() ? "1.0"
                : (cdmVersion.isTextual() ? cdmVersion.textValue() : cdmVersion.asText());
        return new Decision(verdict, List.copyOf(blocking), List.copyOf(warnings), List.copyOf(waived),
                List.copyOf(evaluated), bundleVersion, cdm, 0);
    }

    private static boolean applicable(JsonNode rule, EvalContext ctx) {
        boolean phaseOk = false;
        for (JsonNode phase : rule.path("phases")) {
            if (ctx.phase().equals(phase.asText())) {
                phaseOk = true;
                break;
            }
        }
        if (!phaseOk) {
            return false;
        }
        JsonNode envs = rule.get("environments");
        if (envs == null || envs.isNull()) { // rule.get("environments", ["*"]) default
            return true;
        }
        if (envs.size() == 1 && "*".equals(envs.get(0).asText())) {
            return true;
        }
        for (JsonNode env : envs) {
            if (ctx.environment().equals(env.asText())) {
                return true;
            }
        }
        return false;
    }

    /** Mirror of engine.py _safe: keep the value; truncate its repr past 2048 chars. */
    private static JsonNode safe(JsonNode v) {
        String s = String.valueOf(v);
        return s.length() <= 2048 ? v : TextNode.valueOf(s.substring(0, 2048));
    }
}
