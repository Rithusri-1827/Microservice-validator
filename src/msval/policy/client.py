"""MOD-011 bundle-client (DD-011): fetch active bundle per stage with the fallback ladder.

Ladder: TTL-fresh cache -> version check (ETag) -> fetch -> on transport error:
stale cache + alert, else compile local policy files (files_fallback + WARN).
Transport is injectable for tests; httpx used by default. RISK-008 semantics live HERE.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from msval.config import load as load_config
from .compiler import compile_rules

# transport: (url, headers) -> (status, headers, json_body); raises OSError-family on failure
Transport = Callable[[str, dict[str, str]], tuple[int, dict[str, str], Any]]


def _httpx_transport(url: str, headers: dict[str, str]):
    import httpx
    r = httpx.get(url, headers=headers, timeout=5.0)
    return r.status_code, dict(r.headers), (r.json() if r.status_code == 200 else None)


@dataclass
class BundleClient:
    stage: str
    policies_dir: Path | None = None
    transport: Transport = _httpx_transport
    clock: Callable[[], float] = time.monotonic
    _cache: dict[str, Any] | None = field(default=None, repr=False)
    _etag: str | None = None
    _fetched_at: float = 0.0
    staleness_alerts: int = 0

    def get_rules(self) -> tuple[list[dict[str, Any]], str, str]:
        """Return (rules, bundle_version, source) — source: registry|cache|stale_cache|files_fallback."""
        cfg = load_config()
        if self._cache is not None and self.clock() - self._fetched_at < cfg.bundle_ttl_s:
            return self._cache["rules"], self._cache["version"], "cache"
        base = cfg.registry_url
        try:
            if base is None:
                raise OSError("MSVAL_REGISTRY_URL not configured")
            headers = {"If-None-Match": self._etag} if self._etag else {}
            status, rhead, body = self.transport(f"{base}/api/v1/bundles/active?stage={self.stage}", headers)
            if status == 304 and self._cache is not None:
                self._fetched_at = self.clock()
                return self._cache["rules"], self._cache["version"], "cache"
            if status != 200:
                raise OSError(f"registry returned {status}")
            self._cache = {"rules": body["rules"], "version": body["version"]}
            self._etag = rhead.get("etag")
            self._fetched_at = self.clock()
            return self._cache["rules"], self._cache["version"], "registry"
        except OSError:
            if self._cache is not None:
                self.staleness_alerts += 1
                return self._cache["rules"], self._cache["version"], "stale_cache"
            if self.policies_dir is None:
                raise
            bundle = compile_rules(self.policies_dir)
            return (bundle["stage_sets"][self.stage], bundle["manifest"]["version"], "files_fallback")
