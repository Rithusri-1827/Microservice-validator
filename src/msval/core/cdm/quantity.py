"""F3 — Kubernetes quantity grammar, canonicalization, and quantity expressions.

Canonical integers: cpu -> millicores, memory -> bytes. Both engines implement
identically; TEST-002 pins the edges.
"""
from __future__ import annotations

import re

_QTY_RE = re.compile(r"^(?P<num>[0-9]+(?:\.[0-9]+)?)(?P<unit>m|Ki|Mi|Gi|Ti|k|M|G|T)?$")

_MEM_FACTORS = {"Ki": 1024, "Mi": 1024**2, "Gi": 1024**3, "Ti": 1024**4,
                "k": 1000, "M": 1000**2, "G": 1000**3, "T": 1000**4, None: 1}


class QuantityError(ValueError):
    """Invalid quantity string or dimension mismatch."""


def parse(qty: str, dimension: str) -> int:
    """Parse a quantity string to canonical int (cpu: millicores, memory: bytes)."""
    if dimension not in ("cpu", "memory"):
        raise QuantityError(f"unknown dimension {dimension!r}")
    m = _QTY_RE.match(qty.strip())
    if not m:
        raise QuantityError(f"invalid quantity {qty!r}")
    num, unit = float(m.group("num")), m.group("unit")
    if dimension == "cpu":
        if unit == "m":
            value = num
        elif unit is None:
            value = num * 1000
        else:
            raise QuantityError(f"unit {unit!r} is not a cpu unit in {qty!r}")
    else:
        if unit == "m":
            raise QuantityError(f"'m' is not a memory unit in {qty!r}")
        value = num * _MEM_FACTORS[unit]
    if value < 0:
        raise QuantityError(f"negative quantity {qty!r}")
    return round(value)


_EXPR_TOKEN = re.compile(r"\s*([+-])\s*")


def parse_expr(expr: str, dimension: str) -> int:
    """F3: qty (('+'|'-') qty)* — same-dimension terms only, canonical int result."""
    parts = _EXPR_TOKEN.split(expr.strip())
    if not parts or not parts[0]:
        raise QuantityError(f"empty quantity expression {expr!r}")
    total = parse(parts[0], dimension)
    for i in range(1, len(parts), 2):
        op, term = parts[i], parts[i + 1] if i + 1 < len(parts) else None
        if term is None:
            raise QuantityError(f"dangling operator in {expr!r}")
        total += parse(term, dimension) if op == "+" else -parse(term, dimension)
    return total
