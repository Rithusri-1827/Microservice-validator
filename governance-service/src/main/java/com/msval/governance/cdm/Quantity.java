package com.msval.governance.cdm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F3 — Kubernetes quantity grammar, canonicalization, and quantity expressions.
 * Canonical integers: cpu → millicores, memory → bytes.
 * Behavioural mirror of msval/core/cdm/quantity.py; TEST-002 pins the edges.
 */
public final class Quantity {

    private static final Pattern QTY_RE =
            Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(m|Ki|Mi|Gi|Ti|k|M|G|T)?$");

    private static final Map<String, Double> MEM_FACTORS = Map.of(
            "Ki", Math.pow(1024, 1), "Mi", Math.pow(1024, 2), "Gi", Math.pow(1024, 3), "Ti", Math.pow(1024, 4),
            "k", 1000d, "M", Math.pow(1000, 2), "G", Math.pow(1000, 3), "T", Math.pow(1000, 4));

    private static final Pattern EXPR_TOKEN = Pattern.compile("\\s*([+-])\\s*");

    private Quantity() {
    }

    /** Parse a quantity string to canonical long (cpu: millicores, memory: bytes). */
    public static long parse(String qty, String dimension) {
        if (!"cpu".equals(dimension) && !"memory".equals(dimension)) {
            throw new QuantityException("unknown dimension '" + dimension + "'");
        }
        Matcher m = QTY_RE.matcher(qty.strip());
        if (!m.matches()) {
            throw new QuantityException("invalid quantity '" + qty + "'");
        }
        double num = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        double value;
        if ("cpu".equals(dimension)) {
            if ("m".equals(unit)) {
                value = num;
            } else if (unit == null) {
                value = num * 1000;
            } else {
                throw new QuantityException("unit '" + unit + "' is not a cpu unit in '" + qty + "'");
            }
        } else {
            if ("m".equals(unit)) {
                throw new QuantityException("'m' is not a memory unit in '" + qty + "'");
            }
            value = num * (unit == null ? 1d : MEM_FACTORS.get(unit));
        }
        if (value < 0) {
            throw new QuantityException("negative quantity '" + qty + "'");
        }
        // Python round() is half-to-even; Math.rint matches that (Math.round would be half-up).
        return (long) Math.rint(value);
    }

    /** F3: qty (('+'|'-') qty)* — same-dimension terms only, canonical long result. */
    public static long parseExpr(String expr, String dimension) {
        String s = expr.strip();
        // Mirror re.split(r"\s*([+-])\s*", s): parts alternate term, op, term, ...
        List<String> parts = new ArrayList<>();
        Matcher m = EXPR_TOKEN.matcher(s);
        int last = 0;
        while (m.find()) {
            parts.add(s.substring(last, m.start()));
            parts.add(m.group(1));
            last = m.end();
        }
        parts.add(s.substring(last));
        if (parts.isEmpty() || parts.get(0).isEmpty()) {
            throw new QuantityException("empty quantity expression '" + expr + "'");
        }
        long total = parse(parts.get(0), dimension);
        for (int i = 1; i < parts.size(); i += 2) {
            String op = parts.get(i);
            String term = i + 1 < parts.size() ? parts.get(i + 1) : null;
            if (term == null) {
                throw new QuantityException("dangling operator in '" + expr + "'");
            }
            total += "+".equals(op) ? parse(term, dimension) : -parse(term, dimension);
        }
        return total;
    }
}
