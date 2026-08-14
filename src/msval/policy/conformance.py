"""IF-015 — conformance vector format (owned by policy-kit, DD-010) and loader.

One corpus, two consumers: pytest (engine-py) and JUnit (engine-java). A vector
failing in either engine fails the build — REQ-007's enforcement mechanism.
Layout: policy/conformance/<operator>/CV-*.yaml   (engine-level vectors under engine/)
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, ConfigDict, Field, field_validator

# F9 catalog + 'engine' for whole-decide vectors. Kept in sync with both registries (TEST-004).
OPERATORS = [
    "exists", "equals", "matches_pattern", "in_set", "not_in_set", "range",
    "quantity_compare", "all_match", "none_match_if", "unique_by",
    "graph_all_edges", "graph_node_requires", "promotion_order", "symbolic_capacity",
]
MIN_VECTORS_PER_OPERATOR = 6  # coverage rule (F11); enforced as ERROR once engines land (TASK-010)

_ID_RE = re.compile(r"^CV-[0-9]{4}$")


class Expectation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    pass_: bool = Field(alias="pass")
    reason_code: str | None = None
    path: str | None = None


class Vector(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)
    vector: str
    operator: str
    rule: dict[str, Any]
    input: dict[str, Any]
    context: dict[str, Any] | None = None
    expect: Expectation

    @field_validator("vector")
    @classmethod
    def _id(cls, v: str) -> str:
        if not _ID_RE.match(v):
            raise ValueError(f"vector id {v!r} must match CV-####")
        return v

    @field_validator("operator")
    @classmethod
    def _op(cls, v: str) -> str:
        if v not in OPERATORS and v != "engine":
            raise ValueError(f"unknown operator {v!r}")
        return v


def load_vectors(root: Path) -> list[Vector]:
    """Load and validate every vector under root; raises on any malformed file."""
    vectors: list[Vector] = []
    seen: set[str] = set()
    for f in sorted(root.rglob("CV-*.yaml")):
        data = yaml.safe_load(f.read_text(encoding="utf-8"))
        v = Vector.model_validate(data)
        if v.vector in seen:
            raise ValueError(f"duplicate vector id {v.vector} ({f})")
        seen.add(v.vector)
        vectors.append(v)
    return vectors


def coverage(vectors: list[Vector]) -> dict[str, int]:
    counts = {op: 0 for op in OPERATORS}
    for v in vectors:
        if v.operator in counts:
            counts[v.operator] += 1
    return counts
