"""F7 — the rule file format (DD-010): one YAML doc per rule, validated here.

Params are validated against per-operator schemas; regex operators are linted to the
RE2-safe subset (D4-3); RECON-/DRIFT- prefixes are reserved for synthetic findings (rev 9).
"""
from __future__ import annotations

import re
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from msval.core.cdm.paths import validate_selector
from msval.core.cdm.quantity import QuantityError, parse_expr

FAMILY_PREFIX = {"security": "SEC", "spring-ops": "SPR", "topology": "TOP",
                 "lifecycle": "LCY", "resource": "RES"}
RESERVED_PREFIXES = ("RECON-", "DRIFT-")
PHASES = ("ci", "intake", "runtime")
_ID_RE = re.compile(r"^[A-Z]{3}-[0-9]{3}$")
_UNSAFE_RE = re.compile(r"\\[1-9]|\(\?=|\(\?!|\(\?<")  # backrefs + lookaround = not RE2-safe


class _P(BaseModel):
    model_config = ConfigDict(extra="forbid")


class NoParams(_P): pass
class EqualsParams(_P): value: str | int | float | bool
class PatternParams(_P):
    pattern: str
    @field_validator("pattern")
    @classmethod
    def _safe(cls, v: str) -> str:
        if _UNSAFE_RE.search(v):
            raise ValueError("pattern uses backreferences/lookaround (not RE2-safe, D4-3)")
        re.compile(v)
        return v
class SetParams(_P): values: list[str | int | float | bool] = Field(min_length=1)
class RangeParams(_P):
    min: float | None = None
    max: float | None = None
    @model_validator(mode="after")
    def _bound(self) -> "RangeParams":
        if self.min is None and self.max is None:
            raise ValueError("range needs min and/or max")
        return self
class QuantityCompareParams(_P):
    left: str
    op: str
    right_expr: str
    @field_validator("op")
    @classmethod
    def _op(cls, v: str) -> str:
        if v not in ("gte", "lte", "gt", "lt"):
            raise ValueError("op must be gte|lte|gt|lt")
        return v
    @model_validator(mode="after")
    def _expr(self) -> "QuantityCompareParams":
        try:
            parse_expr(self.right_expr, "memory")
        except QuantityError:
            try:
                parse_expr(self.right_expr, "cpu")
            except QuantityError as e:
                raise ValueError(f"right_expr invalid in both dimensions: {e}")
        return self
class SubkeyEqualsParams(_P): subkey: str; equals: str | int | float | bool
class NoneMatchIfParams(_P):
    if_key: str; if_equals: str | int | float | bool
    then_key: str; forbidden: str | int | float | bool
class UniqueByParams(_P): subkey: str
class GraphEdgesParams(_P): attr: str; equals: str | int | float | bool
class GraphNodeParams(_P):
    if_attr: str; if_equals: str | int | float | bool
    require_attr: str; require_equals: str | int | float | bool
class PromotionOrderParams(_P):
    order: list[str] = Field(min_length=2)
    allowed_skips: list[str] = Field(default_factory=list)
class SymbolicParams(_P):
    dimension: str
    @field_validator("dimension")
    @classmethod
    def _dim(cls, v: str) -> str:
        if v not in ("cpu", "memory"):
            raise ValueError("dimension must be cpu|memory")
        return v


PARAM_MODELS: dict[str, type[BaseModel]] = {
    "exists": NoParams, "equals": EqualsParams, "matches_pattern": PatternParams,
    "in_set": SetParams, "not_in_set": SetParams, "range": RangeParams,
    "quantity_compare": QuantityCompareParams, "all_match": SubkeyEqualsParams,
    "none_match_if": NoneMatchIfParams, "unique_by": UniqueByParams,
    "graph_all_edges": GraphEdgesParams, "graph_node_requires": GraphNodeParams,
    "promotion_order": PromotionOrderParams, "symbolic_capacity": SymbolicParams,
}


class Rule(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str
    family: str
    title: str = Field(max_length=120)
    target: str
    operator: str
    params: dict[str, Any] = Field(default_factory=dict)
    severity: str
    phases: list[str] = Field(min_length=1)
    environments: list[str] = Field(min_length=1)
    message: str
    remediation: str | None = None

    @field_validator("id")
    @classmethod
    def _id(cls, v: str) -> str:
        if not _ID_RE.match(v):
            raise ValueError("rule id must match ^[A-Z]{3}-[0-9]{3}$")
        if any(v.startswith(p) for p in RESERVED_PREFIXES):
            raise ValueError(f"prefixes {RESERVED_PREFIXES} are reserved for synthetic findings")
        return v

    @field_validator("severity")
    @classmethod
    def _sev(cls, v: str) -> str:
        if v not in ("BLOCK", "WARN"):
            raise ValueError("severity must be BLOCK|WARN")
        return v

    @field_validator("phases")
    @classmethod
    def _ph(cls, v: list[str]) -> list[str]:
        bad = [p for p in v if p not in PHASES]
        if bad:
            raise ValueError(f"unknown phases {bad}; allowed {PHASES}")
        return v

    @model_validator(mode="after")
    def _cross(self) -> "Rule":
        prefix = FAMILY_PREFIX.get(self.family)
        if prefix is None:
            raise ValueError(f"unknown family {self.family!r}")
        if not self.id.startswith(prefix + "-"):
            raise ValueError(f"id prefix must match family ({self.family} -> {prefix}-)")
        model = PARAM_MODELS.get(self.operator)
        if model is None:
            raise ValueError(f"unknown operator {self.operator!r}")
        model.model_validate(self.params)
        validate_selector(self.target)
        return self
