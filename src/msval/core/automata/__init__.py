"""MOD-007 (DD-007) — promotion-order operator: environment progression vs governance history."""
from __future__ import annotations


def op_promotion_order(v, p, ctx):
    order, skips = p["order"], set(p.get("allowed_skips", []))
    env = v if isinstance(v, str) else ctx.environment
    if env not in order:
        return False, "ILLEGAL_PROMOTION", f"environment {env!r} not in promotion order {order}"
    idx = order.index(env)
    if idx == 0:
        return True, "OK", ""
    prereq = order[idx - 1]
    history = ctx.promotion_history or []
    if prereq in skips or any(h.get("env") == prereq and h.get("state") == "Validated" for h in history):
        return True, "OK", ""
    return False, "ILLEGAL_PROMOTION", f"version not Validated in prerequisite environment {prereq!r}"
