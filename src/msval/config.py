"""Single configuration surface for every msval process (Stage 4 F12).

Every value is env-overridable; secrets have NO defaults (NFR-004).
Java reads the same MSVAL_* names via Spring @ConfigurationProperties.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field


def _int(name: str, default: int) -> int:
    return int(os.environ.get(name, default))


@dataclass(frozen=True)
class Config:
    queue_bound: int = field(default_factory=lambda: _int("MSVAL_QUEUE_BOUND", 128))
    solver_timeout_ms: int = field(default_factory=lambda: _int("MSVAL_SOLVER_TIMEOUT_MS", 250))
    bundle_ttl_s: int = field(default_factory=lambda: _int("MSVAL_BUNDLE_TTL_S", 30))
    dedup_window_s: int = field(default_factory=lambda: _int("MSVAL_DEDUP_WINDOW_S", 60))
    alert_batch_ms: int = field(default_factory=lambda: _int("MSVAL_ALERT_BATCH_MS", 500))
    settle_delay_s: int = field(default_factory=lambda: _int("MSVAL_SETTLE_DELAY_S", 120))
    report_interval_s: int = field(default_factory=lambda: _int("MSVAL_REPORT_INTERVAL_S", 300))
    sweep_interval_s: int = field(default_factory=lambda: _int("MSVAL_SWEEP_INTERVAL_S", 3600))
    stale_factor: int = field(default_factory=lambda: _int("MSVAL_STALE_FACTOR", 3))
    promote_wait_s: int = field(default_factory=lambda: _int("MSVAL_PROMOTE_WAIT_S", 300))
    ingress_port: int = field(default_factory=lambda: _int("MSVAL_INGRESS_PORT", 7401))
    gateway_host: str = field(default_factory=lambda: os.environ.get("MSVAL_GATEWAY_HOST", "127.0.0.1"))
    gateway_port: int = field(default_factory=lambda: _int("MSVAL_GATEWAY_PORT", 7402))
    alert_port: int = field(default_factory=lambda: _int("MSVAL_ALERT_PORT", 7403))
    registry_url: str | None = field(default_factory=lambda: os.environ.get("MSVAL_REGISTRY_URL"))
    api_token: str | None = field(default_factory=lambda: os.environ.get("MSVAL_API_TOKEN"))


def load() -> Config:
    return Config()
