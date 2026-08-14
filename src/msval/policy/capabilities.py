"""Canonical stage capability registry (DD-010, Stage 1 §4 rev 5).

TEST-004 asserts this stays in sync with the conformance operator catalog; once the
engines land, their registries' descriptors are asserted against it too.
"""
from __future__ import annotations

from msval.policy.conformance import OPERATORS

ALL = frozenset(OPERATORS)
STAGES: dict[str, frozenset[str]] = {
    "ci": ALL,
    "intake": frozenset({"symbolic_capacity"}),               # + schema-level checks (not operators)
    "runtime": ALL - frozenset({"symbolic_capacity"}),
}
# A stage evaluates the rules whose `phases` include its like-named phase.
STAGE_PHASE = {"ci": "ci", "intake": "intake", "runtime": "runtime"}


def stages_for(operator: str, phases: list[str]) -> list[str]:
    """Which stages will run a rule; empty = unroutable (publish error)."""
    return [s for s, ops in STAGES.items() if operator in ops and STAGE_PHASE[s] in phases]
