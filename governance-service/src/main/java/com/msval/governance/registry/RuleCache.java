package com.msval.governance.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.persist.BundleRepo;

/**
 * DD-012 — the in-process rule cache: parse once, swap atomically after activate commits
 * (NFR-008). Stage membership is recomputed from phases × capability (same routing rule
 * as policy-kit's compiler — Capabilities is the mirror).
 */
@Component
public class RuleCache {

    private static final Logger log = LoggerFactory.getLogger(RuleCache.class);

    private final BundleRepo bundles;
    private final AtomicReference<Map<String, RuleSet>> byStage =
            new AtomicReference<>(Map.of());

    public RuleCache(BundleRepo bundles) {
        this.bundles = bundles;
    }

    /** Active rules for one canonical stage id; EMPTY when nothing is active. */
    public RuleSet ruleSetFor(String stage) {
        return byStage.get().getOrDefault(stage, RuleSet.EMPTY);
    }

    /** Stage → active bundle version (for the W4 snapshot frame). */
    public Map<String, String> activeVersions() {
        Map<String, String> out = new LinkedHashMap<>();
        byStage.get().forEach((stage, rs) -> {
            if (rs.version() != null) {
                out.put(stage, rs.version());
            }
        });
        return out;
    }

    /** Reload from active_bundle + policy_rules and swap (startup + after activate). */
    public void swap() {
        Map<String, String> active = bundles.activeVersions();
        Map<String, RuleSet> next = new LinkedHashMap<>();
        for (String stage : Capabilities.STAGES) {
            String version = active.get(stage);
            if (version == null) {
                continue;
            }
            List<JsonNode> rules = new ArrayList<>();
            for (BundleRepo.RuleRow row : bundles.rulesFor(version)) {
                if (Capabilities.stagesFor(row.definition()).contains(stage)) {
                    rules.add(row.definition());
                }
            }
            next.put(stage, new RuleSet(version, List.copyOf(rules)));
        }
        byStage.set(Map.copyOf(next));
        log.info("rule cache swapped: {}", next.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().version()
                        + "(" + e.getValue().rules().size() + " rules)").toList());
    }
}
