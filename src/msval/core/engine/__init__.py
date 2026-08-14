"""MOD-006 engine-py — IF-001 decision contract + IF-002 operator dispatch (DD-006)."""
from .engine import DEFAULT_REGISTRY, decide, evaluate_rule
from .types import CheckResult, Decision, EvalContext, Waiver

__all__ = ["DEFAULT_REGISTRY", "decide", "evaluate_rule",
           "CheckResult", "Decision", "EvalContext", "Waiver"]
