"""DD-005 — rendered-manifest normalizer: multi-doc parse -> instance-aware grouping -> mapping table.

Errors accumulate (never fail-first); unmapped source constructs land in provenance.unmapped_paths;
resources lacking canonical labels are UNGROUPABLE_RESOURCE errors, never silently dropped.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

import yaml

from msval.core.cdm import validate as validate_cdm

NAME_LABEL = "app.kubernetes.io/name"
INSTANCE_LABEL = "app.kubernetes.io/instance"
_XMX_RE = re.compile(r"-Xmx(\d+[kKmMgG]?)")
_PCT_RE = re.compile(r"-XX:MaxRAMPercentage=(\d+(?:\.\d+)?)")


@dataclass(frozen=True)
class NormalizationError:
    file: str
    kind: str
    path: str
    message: str
    severity: str = "error"  # error | warning — unlabeled NON-workload resources warn (real Helm output)


def _labels(doc: dict) -> dict:
    return (doc.get("metadata") or {}).get("labels") or {}


def _group_key(doc: dict) -> tuple[str, str, str] | None:
    meta = doc.get("metadata") or {}
    name = _labels(doc).get(NAME_LABEL)
    if not name:
        return None
    return (meta.get("namespace") or "default", name, _labels(doc).get(INSTANCE_LABEL) or "")


def _parse_image(ref: str) -> dict:
    registry, rest = "", ref
    first = ref.split("/", 1)
    if len(first) == 2 and ("." in first[0] or ":" in first[0] or first[0] == "localhost"):
        registry, rest = first[0], first[1]
    digest = None
    if "@" in rest:
        rest, digest = rest.split("@", 1)
    tag = ""
    if ":" in rest:
        rest, tag = rest.rsplit(":", 1)
    pinned = bool(digest) or (tag not in ("", "latest"))
    return {"repository": rest, "registry": registry, "ref": ref, "pinned": pinned}


def _env_refs(env: list[dict]) -> list[dict]:
    out = []
    for e in env or []:
        src = "literal"
        vf = e.get("valueFrom") or {}
        if "secretKeyRef" in vf:
            src = "secret"
        elif "configMapKeyRef" in vf:
            src = "configmap"
        out.append({"name": e.get("name", ""), "source": src})
    return out


def _probe(p: dict | None, startup: bool = False) -> dict | None:
    if not p or "httpGet" not in p:
        return None
    out = {"path": p["httpGet"].get("path", ""), "port": p["httpGet"].get("port", 0)}
    if startup:
        out["failure_threshold"] = p.get("failureThreshold", 3)
    return out


def _container(c: dict, pod_sec: dict, unmapped: list[str]) -> tuple[dict, dict | None, str | None]:
    """Returns (container_cdm, jvm_or_none, layer_or_none)."""
    sec = {**pod_sec, **(c.get("securityContext") or {})}
    probes = {k: v for k, v in {
        "liveness": _probe(c.get("livenessProbe")),
        "readiness": _probe(c.get("readinessProbe")),
        "startup": _probe(c.get("startupProbe"), startup=True)}.items() if v}
    res = c.get("resources") or {}
    jvm, layer = None, None
    for e in c.get("env") or []:
        if e.get("name") == "COMPONENT_LAYER" and e.get("value"):
            layer = e["value"]
        if e.get("name") == "JAVA_TOOL_OPTIONS" and e.get("value"):
            jvm = {"tool_options": e["value"]}
            m = _XMX_RE.search(e["value"])
            if m:
                v = m.group(1)
                jvm["max_heap"] = v if v[-1].isdigit() else v[:-1] + v[-1].upper() + "i"
            elif _PCT_RE.search(e["value"]):
                unmapped.append("JAVA_TOOL_OPTIONS.MaxRAMPercentage (heap derived from limits at rule time)")
    cdm = {
        "name": c.get("name", ""),
        "image": _parse_image(c.get("image", "")),
        "security": {
            "run_as_non_root": sec.get("runAsNonRoot"),
            "allow_privilege_escalation": sec.get("allowPrivilegeEscalation"),
            "read_only_root_fs": sec.get("readOnlyRootFilesystem"),
            "capabilities_dropped": ((c.get("securityContext") or {}).get("capabilities") or {}).get("drop") or [],
        },
        "env_refs": _env_refs(c.get("env")),
    }
    if res:
        cdm["resources"] = {k: {d: str(v) for d, v in (res.get(k2) or {}).items() if d in ("cpu", "memory")}
                            for k, k2 in (("requests", "requests"), ("limits", "limits")) if res.get(k2)}
    if probes:
        cdm["probes"] = probes
    return cdm, jvm, layer


def normalize_k8s(text: str, environment: str, source: str = "<input>") -> tuple[list[dict], list[NormalizationError]]:
    errors: list[NormalizationError] = []
    try:
        docs = [d for d in yaml.safe_load_all(text) if isinstance(d, dict)]
    except yaml.YAMLError as e:
        return [], [NormalizationError(source, "?", "", f"YAML parse error: {e}")]

    groups: dict[tuple, list[dict]] = {}
    for d in docs:
        key = _group_key(d)
        if key is None:
            kind = d.get("kind", "?")
            sev = "error" if kind in ("Deployment", "StatefulSet", "DaemonSet") else "warning"
            errors.append(NormalizationError(source, kind,
                                             (d.get("metadata") or {}).get("name", "?"),
                                             f"UNGROUPABLE_RESOURCE: missing {NAME_LABEL} label", sev))
            continue
        groups.setdefault(key, []).append(d)

    out: list[dict] = []
    for (ns, name, _instance), members in groups.items():
        by_kind = {}
        for m in members:
            by_kind.setdefault(m.get("kind"), []).append(m)
        deployments = by_kind.get("Deployment", [])
        if not deployments:
            errors.append(NormalizationError(source, "group", f"{ns}/{name}", "no Deployment in service group"))
            continue
        dep = deployments[0]
        unmapped = [f"kind:{k}" for k in by_kind if k not in ("Deployment", "PodDisruptionBudget")]
        spec = dep.get("spec") or {}
        tmpl = (spec.get("template") or {}).get("spec") or {}
        pod_sec = tmpl.get("securityContext") or {}
        containers, jvm, layer = [], None, None
        for c in tmpl.get("containers") or []:
            cdm_c, j, lay = _container(c, pod_sec, unmapped)
            containers.append(cdm_c)
            jvm, layer = jvm or j, layer or lay
        strategy = spec.get("strategy") or {}
        workload: dict[str, Any] = {
            "replicas": spec.get("replicas", 1),
            "strategy": {"type": strategy.get("type", "RollingUpdate"),
                         **{k2: v for k2, v in (("max_surge", (strategy.get("rollingUpdate") or {}).get("maxSurge")),
                                                ("max_unavailable", (strategy.get("rollingUpdate") or {}).get("maxUnavailable")))
                            if v is not None}},
            "termination_grace_seconds": tmpl.get("terminationGracePeriodSeconds", 30),
            "containers": containers,
        }
        if jvm:
            workload["jvm"] = jvm
        for pdb in by_kind.get("PodDisruptionBudget", []):
            ma = (pdb.get("spec") or {}).get("minAvailable")
            if ma is not None:
                workload["pdb"] = {"min_available": ma}
        image = containers[0]["image"] if containers else {"ref": ""}
        tag = image["ref"].split("@", 1)[0].rsplit(":", 1)[-1] if ":" in image["ref"] else "unknown"
        annotations = (dep.get("metadata") or {}).get("annotations") or {}
        doc: dict[str, Any] = {
            "cdm_version": "1.0",
            "service": {"id": name, "name": name, "layer": layer or "Domain"},
            "version": {"tag": tag},
            "environment": environment,
            "workload": workload,
            "provenance": {"source_format": "k8s-rendered-yaml", "source_refs": [f"{source}:{ns}/{name}"],
                           "normalizer_version": "1.0",
                           "unmapped_paths": sorted(set(unmapped)) + ([] if layer else ["COMPONENT_LAYER (defaulted Domain)"])},
        }
        rp = {k2: v for k2, v in (("tls_min", annotations.get("msval/tls-min")),
                                  ("encrypted_volumes", annotations.get("msval/encrypted-volumes"))) if v is not None}
        if rp:
            if isinstance(rp.get("encrypted_volumes"), str):
                rp["encrypted_volumes"] = rp["encrypted_volumes"].lower() == "true"
            doc["runtime_policy"] = rp
        claims = {k2: v for k2, v in (("cpu_expr", annotations.get("msval/cpu-claim")),
                                      ("ram_expr", annotations.get("msval/ram-claim"))) if v}
        if claims:
            claims["vars"] = sorted({v for e in claims.values() for v in re.findall(r"[a-z][a-z0-9_]*", e)})
            doc["resource_claims"] = claims
        for err in validate_cdm(doc):
            errors.append(NormalizationError(source, "Deployment", f"{ns}/{name}", err))
        out.append(doc)
    return out, errors
