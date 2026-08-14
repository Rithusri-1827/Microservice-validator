"""DD-010 — policy-kit compiler: parse -> validate -> capability-route -> immutable bundle.

Bundle version derives from content hash (idempotent republish, IF-006). No clocks here:
`created` is stamped by the registry on publish, not by the compiler.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from typing import Any

import yaml

from .capabilities import stages_for
from .schema import Rule

GRAMMAR_VERSION = "1"


class CompileError(Exception):
    def __init__(self, errors: list[str]):
        super().__init__("; ".join(errors))
        self.errors = errors


def _load_rule_docs(root: Path) -> list[tuple[str, dict[str, Any]]]:
    docs: list[tuple[str, dict[str, Any]]] = []
    for f in sorted(root.rglob("*.yaml")) + sorted(root.rglob("*.yml")):
        for doc in yaml.safe_load_all(f.read_text(encoding="utf-8")):
            if doc is not None:
                docs.append((str(f), doc))
    return docs


def compile_rules(rules_dir: Path | str) -> dict[str, Any]:
    """Compile a rules directory into a bundle dict {manifest, stage_sets}."""
    root = Path(rules_dir)
    errors: list[str] = []
    rules: list[Rule] = []
    seen: set[str] = set()

    for src, doc in _load_rule_docs(root):
        try:
            rule = Rule.model_validate(doc)
        except Exception as exc:
            errors.append(f"{src}: {exc}")
            continue
        if rule.id in seen:
            errors.append(f"{src}: duplicate rule id {rule.id}")
            continue
        seen.add(rule.id)
        rules.append(rule)

    unroutable = [r.id for r in rules if not stages_for(r.operator, r.phases)]
    if unroutable:
        errors.append(f"unroutable rules (no stage supports operator+phase): {unroutable}")
    if errors:
        raise CompileError(errors)

    stage_sets: dict[str, list[dict[str, Any]]] = {"ci": [], "intake": [], "runtime": []}
    for r in rules:
        payload = r.model_dump(exclude_none=True)
        for stage in stages_for(r.operator, r.phases):
            stage_sets[stage].append(payload)

    canonical = json.dumps(stage_sets, sort_keys=True, separators=(",", ":"))
    version = "v" + hashlib.sha256(canonical.encode()).hexdigest()[:12]
    return {
        "manifest": {
            "version": version,
            "grammar_version": GRAMMAR_VERSION,
            "git_commit": os.environ.get("MSVAL_GIT_COMMIT", "unknown"),
            "rule_count": len(rules),
        },
        "stage_sets": stage_sets,
    }
