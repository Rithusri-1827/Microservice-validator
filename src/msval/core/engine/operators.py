"""F9 standard operators (DD-006). Each: (slice_value, params, ctx, rel_doc) -> (passed, reason, detail).

Pure predicates; MISSING handling: only `exists` treats it specially (engine pre-fails others).
"""
from __future__ import annotations

import re
from typing import Any

from msval.core.cdm.paths import MISSING, resolve
from msval.core.cdm.quantity import QuantityError, parse, parse_expr

_PATTERN_CACHE: dict[str, re.Pattern[str]] = {}


def op_exists(v, p, ctx):
    ok = v is not MISSING and v is not None
    return ok, "OK" if ok else "RULE_FAILED", "" if ok else "path missing or null"


def op_equals(v, p, ctx):
    ok = v == p["value"] and type(v) is type(p["value"])
    return ok, "OK" if ok else "RULE_FAILED", "" if ok else f"expected {p['value']!r}, got {v!r}"


def op_matches_pattern(v, p, ctx):
    if not isinstance(v, str):
        return False, "RULE_FAILED", f"not a string: {v!r}"
    pat = _PATTERN_CACHE.setdefault(p["pattern"], re.compile(p["pattern"]))
    ok = pat.fullmatch(v) is not None
    return ok, "OK" if ok else "RULE_FAILED", "" if ok else f"{v!r} does not match {p['pattern']!r}"


def op_in_set(v, p, ctx):
    ok = v in p["values"]
    return ok, "OK" if ok else "RULE_FAILED", "" if ok else f"{v!r} not in approved set"


def op_not_in_set(v, p, ctx):
    ok = v not in p["values"]
    return ok, "OK" if ok else "RULE_FAILED", "" if ok else f"{v!r} is forbidden"


def op_range(v, p, ctx):
    if not isinstance(v, (int, float)) or isinstance(v, bool):
        return False, "RULE_FAILED", f"not numeric: {v!r}"
    lo, hi = p.get("min"), p.get("max")
    ok = (lo is None or v >= lo) and (hi is None or v <= hi)
    return ok, "OK" if ok else "RULE_FAILED", "" if ok else f"{v} outside [{lo}, {hi}]"


def op_quantity_compare(v, p, ctx):
    if not isinstance(v, dict):
        return False, "RULE_FAILED", "slice is not an object"
    slices = resolve(v, p["left"])
    if not slices or slices[0][1] is MISSING:
        return False, "RULE_FAILED", f"left path {p['left']} missing"
    left_raw = slices[0][1]
    for dim in ("memory", "cpu"):
        try:
            left, right = parse(str(left_raw), dim), parse_expr(p["right_expr"], dim)
            break
        except QuantityError:
            continue
    else:
        return False, "RULE_FAILED", f"unparseable quantities {left_raw!r} vs {p['right_expr']!r}"
    ok = {"gte": left >= right, "lte": left <= right, "gt": left > right, "lt": left < right}[p["op"]]
    return ok, "OK" if ok else "RULE_FAILED", "" if ok else f"{left_raw} {p['op']} {p['right_expr']} is false"


def _items(v):
    return v if isinstance(v, list) else None


def op_all_match(v, p, ctx):
    items = _items(v)
    if items is None:
        return False, "RULE_FAILED", "slice is not a list"
    bad = [i for i in items if isinstance(i, dict) and i.get(p["subkey"]) != p["equals"]]
    return (not bad), "OK" if not bad else "RULE_FAILED", "" if not bad else f"{len(bad)} item(s) fail {p['subkey']}={p['equals']}"


def op_none_match_if(v, p, ctx):
    items = _items(v)
    if items is None:
        return False, "RULE_FAILED", "slice is not a list"
    bad = [i for i in items if isinstance(i, dict)
           and i.get(p["if_key"]) == p["if_equals"] and i.get(p["then_key"]) == p["forbidden"]]
    return (not bad), "OK" if not bad else "RULE_FAILED", "" if not bad else f"{len(bad)} forbidden combination(s)"


def op_unique_by(v, p, ctx):
    items = _items(v)
    if items is None:
        return False, "RULE_FAILED", "slice is not a list"
    vals = {str(i.get(p["subkey"])) for i in items if isinstance(i, dict) and i.get(p["subkey"]) is not None}
    ok = len(vals) <= 1
    return ok, "OK" if ok else "RULE_FAILED", "" if ok else f"multiple {p['subkey']} values: {sorted(vals)}"


def op_graph_all_edges(v, p, ctx):
    graph = v if isinstance(v, dict) else (ctx.topology or {})
    bad = [e for e in graph.get("connections", []) if e.get(p["attr"]) != p["equals"]]
    return (not bad), "OK" if not bad else "RULE_FAILED", "" if not bad else f"edges failing {p['attr']}: {[e.get('to') for e in bad]}"


def op_graph_node_requires(v, p, ctx):
    graph = v if isinstance(v, dict) else (ctx.topology or {})
    bad = [n for n in graph.get("nodes", []) if n.get(p["if_attr"]) == p["if_equals"]
           and n.get(p["require_attr"]) != p["require_equals"]]
    return (not bad), "OK" if not bad else "RULE_FAILED", "" if not bad else f"nodes failing requirement: {[n.get('id') for n in bad]}"


STANDARD_OPERATORS: dict[str, Any] = {
    "exists": op_exists, "equals": op_equals, "matches_pattern": op_matches_pattern,
    "in_set": op_in_set, "not_in_set": op_not_in_set, "range": op_range,
    "quantity_compare": op_quantity_compare, "all_match": op_all_match,
    "none_match_if": op_none_match_if, "unique_by": op_unique_by,
    "graph_all_edges": op_graph_all_edges, "graph_node_requires": op_graph_node_requires,
}
