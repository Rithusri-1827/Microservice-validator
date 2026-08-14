package com.msval.governance.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * IF-015 — JUnit-side loader for the shared conformance corpus (TASK-009).
 * Same files as the pytest loader (msval/policy/conformance.py); a vector failing in
 * either engine fails the build — REQ-007's enforcement mechanism.
 *
 * <p>Corpus root: system property {@code msval.conformance.dir}, default
 * {@code ../policy/conformance} (relative to governance-service/).
 */
public final class ConformanceLoader {

    private static final Pattern ID_RE = Pattern.compile("^CV-[0-9]{4}$");

    private ConformanceLoader() {
    }

    public record Expectation(boolean pass, String reasonCode, String path) {
    }

    public record Vector(String id, String operator, JsonNode rule, JsonNode input,
                         JsonNode context, Expectation expect, String file) {
        @Override
        public String toString() {
            return id + "-" + operator; // parameterized-test display name
        }
    }

    public static Path root() {
        return Path.of(System.getProperty("msval.conformance.dir", "../policy/conformance"));
    }

    /** Load and validate every vector under root; throws on any malformed file. */
    public static List<Vector> load() throws IOException {
        Path root = root();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("conformance corpus not found at " + root.toAbsolutePath()
                    + " — set -Dmsval.conformance.dir");
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString();
                        return name.startsWith("CV-") && name.endsWith(".yaml");
                    })
                    .sorted()
                    .toList();
        }
        Yaml yaml = new Yaml();
        ObjectMapper mapper = new ObjectMapper();
        List<Vector> vectors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path f : files) {
            JsonNode data;
            try (InputStream in = Files.newInputStream(f)) {
                Object raw = yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                data = mapper.valueToTree(raw);
            }
            String id = required(data, "vector", f).asText();
            if (!ID_RE.matcher(id).matches()) {
                throw new IllegalStateException("vector id '" + id + "' must match CV-#### (" + f + ")");
            }
            if (!seen.add(id)) {
                throw new IllegalStateException("duplicate vector id " + id + " (" + f + ")");
            }
            String operator = required(data, "operator", f).asText();
            JsonNode rule = required(data, "rule", f);
            JsonNode input = required(data, "input", f);
            JsonNode context = data.hasNonNull("context") ? data.get("context") : null;
            JsonNode expectNode = required(data, "expect", f);
            Expectation expect = new Expectation(
                    required(expectNode, "pass", f).asBoolean(),
                    expectNode.hasNonNull("reason_code") ? expectNode.get("reason_code").asText() : null,
                    expectNode.hasNonNull("path") ? expectNode.get("path").asText() : null);
            vectors.add(new Vector(id, operator, rule, input, context, expect, f.toString()));
        }
        return vectors;
    }

    private static JsonNode required(JsonNode node, String key, Path f) {
        JsonNode n = node.get(key);
        if (n == null) {
            throw new IllegalStateException("missing '" + key + "' in " + f);
        }
        return n;
    }
}
