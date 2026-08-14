package com.msval.governance.cdm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * F2 — CDM path selectors: grammar, resolution, MISSING sentinel.
 * Behavioural mirror of msval/core/cdm/paths.py (engine-py); conformance vectors pin both.
 */
public final class PathResolver {

    public static final int MAX_DEPTH = 8;
    public static final int MAX_FANOUT = 256;

    /**
     * Sentinel distinct from JSON null: the path does not exist in the document.
     * Jackson's MissingNode singleton never occurs inside a parsed document, so
     * {@link JsonNode#isMissingNode()} is a safe identity test.
     */
    public static final JsonNode MISSING = MissingNode.getInstance();

    private static final Pattern SEGMENT_RE = Pattern.compile("^[a-z_][a-z0-9_]*(\\[\\])?$");

    private PathResolver() {
    }

    public static boolean isMissing(JsonNode node) {
        return node != null && node.isMissingNode();
    }

    /** Validate the selector grammar; returns the segments. Throws BAD_SELECTOR otherwise. */
    public static List<String> validateSelector(String selector) {
        String[] segments = selector.split("\\.", -1);
        if (segments.length == 0 || segments.length > MAX_DEPTH) {
            throw new SelectorException("BAD_SELECTOR", "depth 1.." + MAX_DEPTH + " violated: '" + selector + "'");
        }
        for (String seg : segments) {
            if (!SEGMENT_RE.matcher(seg).matches()) {
                throw new SelectorException("BAD_SELECTOR", "bad segment '" + seg + "' in '" + selector + "'");
            }
        }
        return List.of(segments);
    }

    /**
     * Resolve a selector to [(concrete_path, value)] in document order.
     *
     * <p>Missing final segment yields (path, MISSING); missing intermediate yields the
     * branch's (path_so_far, MISSING) once. '[]' fans out over lists.
     */
    public static List<Slice> resolve(JsonNode doc, String selector) {
        List<String> segments = validateSelector(selector);
        List<Slice> results = new ArrayList<>();
        walk(doc, 0, "", segments, results, selector);
        if (results.size() > MAX_FANOUT) {
            throw new SelectorException("FANOUT", "more than " + MAX_FANOUT + " slices for '" + selector + "'");
        }
        return results;
    }

    private static void walk(JsonNode node, int idx, String path,
                             List<String> segments, List<Slice> results, String selector) {
        if (results.size() > MAX_FANOUT) {
            throw new SelectorException("FANOUT", "more than " + MAX_FANOUT + " slices for '" + selector + "'");
        }
        if (idx == segments.size()) {
            results.add(new Slice(path, node));
            return;
        }
        String seg = segments.get(idx);
        boolean iterate = seg.endsWith("[]");
        String key = iterate ? seg.substring(0, seg.length() - 2) : seg;
        if (node == null || !node.isObject() || !node.has(key) || node.get(key).isNull()) {
            results.add(new Slice(path.isEmpty() ? key : path + "." + key, MISSING));
            return;
        }
        JsonNode child = node.get(key);
        String childPath = path.isEmpty() ? key : path + "." + key;
        if (iterate) {
            if (!child.isArray()) {
                throw new SelectorException("BAD_SELECTOR", childPath + " is not a list");
            }
            for (int i = 0; i < child.size(); i++) {
                walk(child.get(i), idx + 1, childPath + "[" + i + "]", segments, results, selector);
            }
        } else {
            walk(child, idx + 1, childPath, segments, results, selector);
        }
    }
}
