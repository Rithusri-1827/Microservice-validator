"""F5/F10 — Decision types and EvalContext (DD-006). Pure data, no I/O."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class CheckResult:
    rule_id: str
    path: str
    passed: bool
    reason_code: str = "OK"
    detail: str = ""
    observed: Any = None


@dataclass(frozen=True)
class Waiver:
    waiver_id: int
    rule_id: str
    service_id: str
    version: str
    environment: str
    expires_at: str  # ISO


@dataclass(frozen=True)
class EvalContext:
    phase: str = "ci"
    environment: str = "test"
    capacity: dict[str, int] | None = None
    promotion_history: list[dict[str, str]] | None = None
    live_state: dict[str, Any] | None = None
    declared_baseline: dict[str, Any] | None = None
    topology: dict[str, Any] | None = None
    waivers: list[Waiver] = field(default_factory=list)
    now: str = "1970-01-01T00:00:00Z"  # injected for determinism

    @classmethod
    def from_dict(cls, d: dict[str, Any] | None) -> "EvalContext":
        d = dict(d or {})
        d["waivers"] = [Waiver(**w) if isinstance(w, dict) else w for w in d.get("waivers", [])]
        return cls(**{k: v for k, v in d.items() if k in cls.__dataclass_fields__})


@dataclass(frozen=True)
class Decision:
    verdict: str  # PASS | FAIL | ERROR
    blocking: list[CheckResult]
    warnings: list[CheckResult]
    waived: list[tuple[CheckResult, int]]
    evaluated_rules: list[str]
    evaluated_under: str
    cdm_version: str = "1.0"
    duration_ms: int = 0
