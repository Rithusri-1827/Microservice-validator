"""MOD-008 (DD-008) — symbolic capacity operator: guard -> pooled SymPy -> timeout.

Timeout counts as a verdict (SOLVER_TIMEOUT), never a hang (ADR-006, RISK-001).
"""
from __future__ import annotations

from concurrent.futures import ProcessPoolExecutor, TimeoutError as PoolTimeout

from msval.config import load as load_config
from .guard import validate_claim

_POOL: ProcessPoolExecutor | None = None

import atexit


@atexit.register
def _shutdown_pool() -> None:
    """Prevent orphaned solver workers when the parent dies (observed 2026-08-14)."""
    if _POOL is not None:
        _POOL.shutdown(wait=False, cancel_futures=True)


def _solve(expr: str, var: str, capacity: int) -> bool:
    from sympy import S, Symbol, reduce_inequalities, sympify
    sym = Symbol(var, positive=True)
    e = sympify(expr, locals={var: sym})
    result = reduce_inequalities(e <= capacity, sym)
    # sympy returns S.false (BooleanFalse), NOT Python False — `is not False` was always
    # true and every unsatisfiable claim passed (bug caught live in TASK-028; vector CV-0037
    # now pins it).
    return result is not S.false and result is not False


def _warm() -> bool:
    import sympy  # noqa: F401 — pay the import cost at pool creation, not on the timed path
    return True


def _pool() -> ProcessPoolExecutor:
    global _POOL
    if _POOL is None:
        _POOL = ProcessPoolExecutor(max_workers=2)
        _POOL.submit(_warm).result()  # untimed warmup (DD-008 initializer intent)
    return _POOL


def op_symbolic_capacity(v, p, ctx):
    expr = v if isinstance(v, str) else None
    if not expr:
        return True, "OK", "no claim declared"
    errors = validate_claim(expr)
    if errors:
        return False, "UNSOLVABLE:GRAMMAR", "; ".join(errors)
    capacity = (ctx.capacity or {}).get(p["dimension"])
    if capacity is None:
        return True, "OK", f"no {p['dimension']} capacity in context (CI without env record) — solvability not asserted"
    import re
    var = re.findall(r"[a-z][a-z0-9_]*", expr)[0]
    timeout_s = load_config().solver_timeout_ms / 1000
    global _POOL
    try:
        sat = _pool().submit(_solve, expr, var, capacity).result(timeout=timeout_s)
    except PoolTimeout:
        _POOL.shutdown(wait=False, cancel_futures=True)
        _POOL = None
        return False, "SOLVER_TIMEOUT", f"solver exceeded {timeout_s * 1000:.0f} ms"
    except NotImplementedError:
        return False, "UNSOLVABLE:SOLVER", "sympy cannot reduce this inequality"
    except Exception as exc:
        return False, "UNSOLVABLE:SOLVER", f"solver error: {exc}"
    if sat:
        return True, "OK", f"claim satisfiable within {p['dimension']} capacity {capacity}"
    return False, "UNSOLVABLE:SOLVER", f"no value satisfies {expr} <= {capacity}"
