package com.msval.governance.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.persist.AuditRepo;
import com.msval.governance.persist.BundleRepo;
import com.msval.governance.support.HttpError;

/**
 * DD-012 — bundle store behaviour: immutable publish (idempotent by content hash),
 * atomic activate (active pointer flip + audit + cache swap after commit), fetch views.
 */
@Service
public class RegistryService {

    public record PublishResult(String version, boolean created) {
    }

    public record BundleView(String version, JsonNode manifest, List<JsonNode> rules) {
    }

    private final BundleRepo bundles;
    private final AuditRepo audit;
    private final RuleCache cache;
    private final TransactionTemplate txn;

    public RegistryService(BundleRepo bundles, AuditRepo audit, RuleCache cache, TransactionTemplate txn) {
        this.bundles = bundles;
        this.audit = audit;
        this.cache = cache;
        this.txn = txn;
    }

    /** IF-006 publish: validate → hash lookup → insert bundle + rules + audit (one txn). */
    public PublishResult publish(JsonNode bundle) {
        JsonNode manifest = bundle.path("manifest");
        String version = manifest.path("version").asText("");
        JsonNode stageSets = bundle.path("stage_sets");
        List<String> errors = new ArrayList<>();
        if (version.isEmpty()) {
            errors.add("manifest.version is required");
        }
        if (!stageSets.isObject()) {
            errors.add("stage_sets must be an object {stage: [rules]}");
        }
        if (!errors.isEmpty()) {
            throw HttpError.unprocessable("malformed bundle", errors);
        }

        Map<String, JsonNode> rulesById = new LinkedHashMap<>();
        List<String> unroutable = new ArrayList<>();
        for (Iterator<String> it = stageSets.fieldNames(); it.hasNext(); ) {
            String stage = it.next();
            if (!Capabilities.STAGES.contains(stage)) {
                errors.add("unknown stage id '" + stage + "' (canonical: ci|intake|runtime)");
                continue;
            }
            for (JsonNode rule : stageSets.get(stage)) {
                String rid = rule.path("id").asText("");
                if (rid.isEmpty() || rule.path("operator").asText("").isEmpty()) {
                    errors.add(stage + ": rule without id/operator");
                    continue;
                }
                if (rid.startsWith("RECON-") || rid.startsWith("DRIFT-")) {
                    errors.add(rid + ": RECON-/DRIFT- prefixes are reserved for synthetic findings (IF-014)");
                    continue;
                }
                rulesById.putIfAbsent(rid, rule);
            }
        }
        for (JsonNode rule : rulesById.values()) {
            if (Capabilities.stagesFor(rule).isEmpty()) {
                unroutable.add(rule.path("id").asText());
            }
        }
        if (!unroutable.isEmpty()) {
            throw new UnroutableBundle(HttpError.unprocessable("bundle rejected", errors), unroutable);
        }
        if (!errors.isEmpty()) {
            throw HttpError.unprocessable("bundle rejected", errors);
        }

        String hash = sha256(canonical(stageSets));
        return txn.execute(status -> {
            var existing = bundles.versionByHash(hash);
            if (existing.isPresent()) {
                return new PublishResult(existing.get(), false); // 200 idempotent republish
            }
            if (bundles.exists(version)) {
                throw HttpError.unprocessable("bundle version " + version
                        + " already exists with different content", List.of());
            }
            bundles.insertBundle(version, manifest.path("git_commit").asText(null), hash,
                    manifest.path("grammar_version").asText("1"), manifest, "api");
            for (JsonNode rule : rulesById.values()) {
                bundles.insertRule(version, rule.path("id").asText(),
                        rule.path("family").asText(""), rule.path("severity").asText(""),
                        texts(rule.path("phases")), texts(rule.path("environments")), rule);
            }
            audit.record(version, "publish", "api");
            return new PublishResult(version, true);
        });
    }

    /** IF-006 activate: verify → flip all stage pointers + audit → swap cache after commit. */
    public Map<String, String> activate(String version) {
        Map<String, String> stageVersions = txn.execute(status -> {
            if (!bundles.exists(version)) {
                throw HttpError.notFound("no bundle " + version);
            }
            for (String stage : Capabilities.STAGES) {
                bundles.setActive(stage, version);
            }
            audit.record(version, "activate", "api");
            return bundles.activeVersions();
        });
        cache.swap(); // after commit (DD-012)
        return stageVersions;
    }

    /** W5 GET /bundles/active?stage= — served from the cache. */
    public BundleView active(String stage) {
        requireStage(stage);
        RuleSet rs = cache.ruleSetFor(stage);
        if (rs.version() == null) {
            throw HttpError.notFound("no active bundle for stage " + stage);
        }
        JsonNode manifest = bundles.manifest(rs.version()).orElse(null);
        return new BundleView(rs.version(), manifest, rs.rules());
    }

    /** W5 GET /bundles/{v}?stage= — reproducibility fetch straight from the store. */
    public BundleView byVersion(String version, String stage) {
        requireStage(stage);
        JsonNode manifest = bundles.manifest(version)
                .orElseThrow(() -> HttpError.notFound("no bundle " + version));
        List<JsonNode> rules = new ArrayList<>();
        for (BundleRepo.RuleRow row : bundles.rulesFor(version)) {
            if (Capabilities.stagesFor(row.definition()).contains(stage)) {
                rules.add(row.definition());
            }
        }
        return new BundleView(version, manifest, rules);
    }

    public Map<String, String> stageVersions() {
        return bundles.activeVersions();
    }

    private static void requireStage(String stage) {
        if (stage == null || !Capabilities.STAGES.contains(stage)) {
            throw HttpError.unprocessable("stage must be one of " + Capabilities.STAGES, List.of());
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            out.add(n.asText());
        }
        return out;
    }

    /** Canonical JSON: keys sorted recursively, compact — stable content hash server-side. */
    static String canonical(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        canonicalize(node, sb);
        return sb.toString();
    }

    private static void canonicalize(JsonNode node, StringBuilder sb) {
        if (node.isObject()) {
            sb.append('{');
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey().replace("\\", "\\\\").replace("\"", "\\\""))
                        .append("\":");
                canonicalize(e.getValue(), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonNode n : node) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                canonicalize(n, sb);
            }
            sb.append(']');
        } else {
            sb.append(node.toString());
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 422 carrying the offending rule ids (IF-006: {unroutable:[…], errors}). */
    public static final class UnroutableBundle extends RuntimeException {
        public final HttpError error;
        public final List<String> unroutable;

        UnroutableBundle(HttpError error, List<String> unroutable) {
            super(error.getMessage());
            this.error = error;
            this.unroutable = List.copyOf(new LinkedHashSet<>(unroutable));
        }
    }
}
