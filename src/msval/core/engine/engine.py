"""DD-006 — engine-py: the rule-agnostic interpreter (IF-001). Pure; no I/O, no clocks."""
from __future__ import annotations

from typing import Any, Callable

from msval.core.automata import op_promotion_order
from msval.core.cdm.paths import MISSING, SelectorError, resolve
from msval.core.symbolic.solver import op_symbolic_capacity

from .operators import STANDARD_OPERATORS
from .types import CheckResult, Decision, EvalContext

Operator = Callable[[Any, dict[str, Any], EvalContext], tuple[bool, str, str]]

DEFAULT_REGISTRY: dict[str, Operator] = {
    **STANDARD_OPERATORS,
    "promotion_order": op_promotion_order,
    "symbolic_capacity": op_symbolic_capacity,
}


def _applicable(rule: dict[str, Any], ctx: EvalContext) -> bool:
    if ctx.phase not in rule.get("phases", []):
        return False
    envs = rule.get("environments", ["*"])
    return envs == ["*"] or ctx.environment in envs


def evaluate_rule(doc: dict[str, Any], rule: dict[str, Any], ctx: EvalContext,
                  registry: dict[str, Operator] = DEFAULT_REGISTRY) -> list[CheckResult]:
    """All CheckResults (pass and fail) for one rule against one document."""
    rid = rule["id"]
    op = registry.get(rule["operator"])
    if op is None:
        return [CheckResult(rid, rule["target"], False, "ENGINE_FAULT:UNKNOWN_OPERATOR", rule["operator"])]
    try:
        slices = resolve(doc, rule["target"])
    except SelectorError as e:
        return [CheckResult(rid, rule["target"], False, f"ENGINE_FAULT:{e.code}", str(e))]
    if not slices:
        slices = [(rule["target"], MISSING)]
    out: list[CheckResult] = []
    MISSING_TOLERANT = ("exists", "symbolic_capacity")  # these define their own MISSING semantics
    for path, value in slices:
        if value is MISSING and rule["operator"] in MISSING_TOLERANT and rule["operator"] != "exists":
            value = None  # symbolic: no claim declared -> operator returns OK
        if value is MISSING and rule["operator"] != "exists":
            out.append(CheckResult(rid, path, False, "RULE_FAILED", "path missing"))
            continue
        try:
            passed, reason, detail = op(value, rule.get("params", {}), ctx)
        except Exception as exc:  # operator bug or bad params that escaped publish lint
            out.append(CheckResult(rid, path, False, "ENGINE_FAULT:BAD_PARAMS", str(exc)))
            continue
        out.append(CheckResult(rid, path, passed, reason, detail,
                               observed=None if passed else _safe(value)))
    return out


def _safe(v: Any) -> Any:
    s = repr(v)
    return v if len(s) <= 2048 else s[:2048]


def decide(doc: dict[str, Any], rules: list[dict[str, Any]], ctx: EvalContext,
           bundle_version: str = "unversioned",
           registry: dict[str, Operator] = DEFAULT_REGISTRY) -> Decision:
    blocking: list[CheckResult] = []
    warnings: list[CheckResult] = []
    waived: list[tuple[CheckResult, int]] = []
    evaluated: list[str] = []
    fault = False
    service = doc.get("service", {}) if isinstance(doc.get("service"), dict) else {}
    sid, ver = service.get("id", ""), doc.get("version", {}).get("tag", "") if isinstance(doc.get("version"), dict) else ""

    for rule in rules:
        if not _applicable(rule, ctx):
            continue
        evaluated.append(rule["id"])
        for r in evaluate_rule(doc, rule, ctx, registry):
            if r.passed:
                continue
            if r.reason_code.startswith("ENGINE_FAULT"):
                fault = True
                blocking.append(r)
                continue
            w = next((w for w in ctx.waivers if w.rule_id == r.rule_id and w.service_id == sid
                      and w.version == ver and w.environment == ctx.environment
                      and w.expires_at > ctx.now), None)
            if w is not None:
                waived.append((r, w.waiver_id))
            elif rule.get("severity") == "WARN":
                warnings.append(r)
            else:
                blocking.append(r)

    key = lambda r: (r.rule_id, r.path)
    verdict = "ERROR" if fault else ("FAIL" if blocking else "PASS")
    return Decision(verdict=verdict,
                    blocking=sorted(blocking, key=key),
                    warnings=sorted(warnings, key=key),
                    waived=sorted(waived, key=lambda t: key(t[0])),
                    evaluated_rules=sorted(evaluated),
                    evaluated_under=bundle_version,
                    cdm_version=str(doc.get("cdm_version", "1.0")))
