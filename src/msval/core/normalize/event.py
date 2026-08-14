"""DD-005 — event-JSON normalizer (trivial mapping; Java mirrors it for IF-012, vector-pinned)."""
from __future__ import annotations

from typing import Any

from msval.core.cdm import validate as validate_cdm


def normalize_event(payload: dict[str, Any]) -> tuple[dict[str, Any] | None, list[str]]:
    """Deployment events carry a full approved_cdm; status reports carry live_state fragments.

    Returns (cdm_doc | None, errors). For status reports the live_state containers are wrapped
    into a comparable minimal CDM (drift comparison happens field-wise in the assembler, D4-4).
    """
    kind = payload.get("kind")
    if kind == "deployment":
        doc = payload.get("approved_cdm")
        if not isinstance(doc, dict):
            return None, ["deployment event without approved_cdm (A-3 fallback: baseline unavailable)"]
        return doc, validate_cdm(doc)
    if kind == "status_report":
        live = payload.get("live_state") or {}
        doc = {
            "cdm_version": "1.0",
            "service": {"id": payload.get("service_id", ""), "name": payload.get("service_id", ""),
                        "layer": live.get("layer", "Domain")},
            "version": {"tag": payload.get("service_version", "unknown")},
            "environment": payload.get("environment", ""),
            "workload": {"replicas": live.get("replicas", 1),
                         "containers": live.get("containers") or [{"name": "unknown", "image": {
                             "repository": "", "registry": "", "ref": "", "pinned": False}}]},
            "provenance": {"source_format": "event-json",
                           "source_refs": [payload.get("event_id", "")],
                           "normalizer_version": "1.0", "unmapped_paths": []},
        }
        return doc, validate_cdm(doc)
    return None, [f"unknown event kind {kind!r}"]
