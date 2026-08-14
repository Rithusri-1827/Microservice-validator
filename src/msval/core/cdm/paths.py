"""F2 — CDM path selectors: grammar, resolution, MISSING sentinel.

resolve() is one of the two behaviours (with quantities) most heavily pinned by
conformance vectors, because both engines implement it independently.
"""
from __future__ import annotations

import re
from typing import Any

_SEGMENT_RE = re.compile(r"^[a-z_][a-z0-9_]*(\[\])?$")
MAX_DEPTH = 8
MAX_FANOUT = 256


class Missing:
    """Sentinel distinct from None: the path does not exist in the document."""
    _instance: "Missing | None" = None

    def __new__(cls) -> "Missing":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __repr__(self) -> str:
        return "MISSING"


MISSING = Missing()


class SelectorError(ValueError):
    """ENGINE_FAULT-class selector problem (BAD_SELECTOR / FANOUT). Publish-time bug."""

    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code


def validate_selector(selector: str) -> list[str]:
    segments = selector.split(".")
    if not segments or len(segments) > MAX_DEPTH:
        raise SelectorError("BAD_SELECTOR", f"depth 1..{MAX_DEPTH} violated: {selector!r}")
    for seg in segments:
        if not _SEGMENT_RE.match(seg):
            raise SelectorError("BAD_SELECTOR", f"bad segment {seg!r} in {selector!r}")
    return segments


def resolve(doc: dict[str, Any], selector: str) -> list[tuple[str, Any]]:
    """Resolve a selector to [(concrete_path, value)] in document order.

    Missing final segment yields (path, MISSING); missing intermediate yields the
    branch's (path_so_far, MISSING) once. '[]' fans out over lists.
    """
    segments = validate_selector(selector)
    results: list[tuple[str, Any]] = []

    def walk(node: Any, idx: int, path: str) -> None:
        if len(results) > MAX_FANOUT:
            raise SelectorError("FANOUT", f"more than {MAX_FANOUT} slices for {selector!r}")
        if idx == len(segments):
            results.append((path, node))
            return
        seg = segments[idx]
        iterate = seg.endswith("[]")
        key = seg[:-2] if iterate else seg
        if not isinstance(node, dict) or key not in node or node[key] is None:
            results.append((f"{path}.{key}" if path else key, MISSING))
            return
        child = node[key]
        child_path = f"{path}.{key}" if path else key
        if iterate:
            if not isinstance(child, list):
                raise SelectorError("BAD_SELECTOR", f"{child_path} is not a list")
            for i, item in enumerate(child):
                walk(item, idx + 1, f"{child_path}[{i}]")
        else:
            walk(child, idx + 1, child_path)

    walk(doc, 0, "")
    if len(results) > MAX_FANOUT:
        raise SelectorError("FANOUT", f"more than {MAX_FANOUT} slices for {selector!r}")
    return results
