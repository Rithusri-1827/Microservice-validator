"""F4 — resource-claim expression grammar guard (DD-008). Solver arrives in TASK-008.

Shared by the symbolic operator (evaluation-time defense) and policy-kit/normalizers
(publish/CI-time lint) — the same grammar, one implementation.
"""
from __future__ import annotations

import re

_TERM = r"(?:\d+(?:\.\d+)?(?:\s*\*\s*[a-z][a-z0-9_]*)?|[a-z][a-z0-9_]*(?:\s*\*\*\s*[123])?)"
_EXPR_RE = re.compile(rf"^\s*{_TERM}(?:\s*[+-]\s*{_TERM})*\s*$")
_VAR_RE = re.compile(r"[a-z][a-z0-9_]*")


def validate_claim(expr: str) -> list[str]:
    """Return error strings ([] = valid): F4 grammar + exactly one distinct variable."""
    if not _EXPR_RE.match(expr):
        return [f"claim expression violates the F4 grammar: {expr!r}"]
    variables = set(_VAR_RE.findall(expr))
    if len(variables) > 1:
        return [f"claim must be univariate; found {sorted(variables)}"]
    return []
