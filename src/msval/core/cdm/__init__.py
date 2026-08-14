"""MOD-004 `cdm` — canonical descriptor model, path addressing, quantities (DD-004).

Contract surface (IF-004): CDMDocument, validate, resolve, MISSING, quantity parse/parse_expr,
emit_schema. High-fan-in stable kernel — changes are vector-gated (IF-015).
"""
from .model import CDM_MAJOR, CDMDocument, emit_schema, validate
from .paths import MISSING, SelectorError, resolve, validate_selector
from .quantity import QuantityError, parse, parse_expr

__all__ = [
    "CDM_MAJOR", "CDMDocument", "emit_schema", "validate",
    "MISSING", "SelectorError", "resolve", "validate_selector",
    "QuantityError", "parse", "parse_expr",
]
