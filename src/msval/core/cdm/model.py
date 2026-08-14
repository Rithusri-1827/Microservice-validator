"""F1 — CDM v1: the canonical descriptor model (pydantic v2, D4-1).

The emitted JSON Schema (emit_schema) is the canonical spec the Java mirror is
tested against (TEST-002). Additive-only within a major; engines refuse unknown majors.
"""
from __future__ import annotations

import json
import re
from enum import Enum
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .quantity import QuantityError, parse as parse_qty

CDM_MAJOR = 1
_SERVICE_ID_RE = re.compile(r"^[a-z0-9-]{1,63}$")
_CLAIM_VAR_RE = re.compile(r"[a-z][a-z0-9_]*")


class _Base(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Layer(str, Enum):
    Edge = "Edge"; Aggregator = "Aggregator"; Domain = "Domain"; Integrator = "Integrator"; Broker = "Broker"


class Service(_Base):
    id: str
    name: str
    layer: Layer
    team: str | None = None

    @field_validator("id")
    @classmethod
    def _id(cls, v: str) -> str:
        if not _SERVICE_ID_RE.match(v):
            raise ValueError("service.id must match [a-z0-9-]{1,63}")
        return v


class Version(_Base):
    tag: str
    image_digest: str | None = None


class Strategy(_Base):
    type: str = "RollingUpdate"
    max_surge: int | str | None = None
    max_unavailable: int | str | None = None


class Pdb(_Base):
    min_available: int | str


class Image(_Base):
    repository: str
    registry: str = ""
    ref: str
    pinned: bool


class Security(_Base):
    run_as_non_root: bool | None = None
    allow_privilege_escalation: bool | None = None
    read_only_root_fs: bool | None = None
    capabilities_dropped: list[str] = Field(default_factory=list)


class ResourcePair(_Base):
    cpu: str | None = None
    memory: str | None = None

    @model_validator(mode="after")
    def _qty(self) -> "ResourcePair":
        for dim in ("cpu", "memory"):
            v = getattr(self, dim)
            if v is not None:
                parse_qty(v, dim)  # raises QuantityError -> ValidationError
        return self


class Resources(_Base):
    requests: ResourcePair | None = None
    limits: ResourcePair | None = None


class Probe(_Base):
    path: str
    port: int


class StartupProbe(Probe):
    failure_threshold: int = 3


class Probes(_Base):
    liveness: Probe | None = None
    readiness: Probe | None = None
    startup: StartupProbe | None = None


class EnvRef(_Base):
    name: str
    source: str  # literal | configmap | secret

    @field_validator("source")
    @classmethod
    def _src(cls, v: str) -> str:
        if v not in ("literal", "configmap", "secret"):
            raise ValueError("env_refs.source must be literal|configmap|secret")
        return v


class Container(_Base):
    name: str
    image: Image
    security: Security = Field(default_factory=Security)
    resources: Resources | None = None
    probes: Probes | None = None
    env_refs: list[EnvRef] = Field(default_factory=list)


class Jvm(_Base):
    max_heap: str | None = None
    tool_options: str | None = None


class Workload(_Base):
    replicas: int = Field(ge=0)
    strategy: Strategy = Field(default_factory=Strategy)
    termination_grace_seconds: int = Field(default=30, ge=0, le=3600)
    pdb: Pdb | None = None
    containers: list[Container] = Field(min_length=1)
    jvm: Jvm | None = None


class RuntimePolicy(_Base):
    tls_min: str | None = None
    encrypted_volumes: bool | None = None


class ResourceClaims(_Base):
    cpu_expr: str | None = None
    ram_expr: str | None = None
    vars: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def _vars(self) -> "ResourceClaims":
        used: set[str] = set()
        for expr in (self.cpu_expr, self.ram_expr):
            if expr:
                used |= set(_CLAIM_VAR_RE.findall(expr))
        if not set(self.vars) >= used:
            raise ValueError(f"resource_claims.vars must cover variables {sorted(used)}")
        return self


class Connection(_Base):
    to: str = Field(min_length=1)
    authenticated: bool
    authorized: bool
    encrypted: bool


class Logging(_Base):
    enabled: bool
    direct_central: bool


class Topology(_Base):
    entry_point: bool = False
    connections: list[Connection] = Field(default_factory=list)
    logging: Logging | None = None


class Provenance(_Base):
    source_format: str  # k8s-rendered-yaml | event-json
    source_refs: list[str] = Field(default_factory=list)
    normalizer_version: str
    unmapped_paths: list[str] = Field(default_factory=list)


class CDMDocument(_Base):
    cdm_version: str = "1.0"
    service: Service
    version: Version
    environment: str
    workload: Workload
    runtime_policy: RuntimePolicy | None = None
    resource_claims: ResourceClaims | None = None
    topology: Topology | None = None
    provenance: Provenance

    @field_validator("cdm_version")
    @classmethod
    def _major(cls, v: str) -> str:
        major = v.split(".", 1)[0]
        if not major.isdigit() or int(major) != CDM_MAJOR:
            raise ValueError(f"unsupported cdm_version major in {v!r} (engine implements {CDM_MAJOR}.x)")
        return v


def validate(doc: dict[str, Any]) -> list[str]:
    """Validate a raw dict; return error strings with JSON-pointer-ish locations ([] = valid)."""
    try:
        CDMDocument.model_validate(doc)
        return []
    except Exception as exc:  # pydantic ValidationError
        errors = getattr(exc, "errors", None)
        if errors is None:
            return [str(exc)]
        return [f"/{'/'.join(str(p) for p in e['loc'])}: {e['msg']}" for e in errors()]


def emit_schema(out: Path | str = "cdm-v1.schema.json") -> Path:
    """Write the canonical JSON Schema (consumed by the Java mirror's tests)."""
    path = Path(out)
    path.write_text(json.dumps(CDMDocument.model_json_schema(), indent=2), encoding="utf-8")
    return path


_ = QuantityError  # re-exported failure type for callers
