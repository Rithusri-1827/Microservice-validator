"""MOD-005 normalizers (DD-005): rendered K8s YAML -> CDM; event JSON -> CDM."""
from .k8s import NormalizationError, normalize_k8s
from .event import normalize_event

NORMALIZER_VERSION = "1.0"
__all__ = ["normalize_k8s", "normalize_event", "NormalizationError", "NORMALIZER_VERSION"]
