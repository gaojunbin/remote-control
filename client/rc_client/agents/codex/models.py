"""Codex model catalogue read from `model/list`, cached for 10 minutes."""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any

from ...logging_setup import logger
from ...models import Choice
from .rpc import one_shot

log = logger("rc_client.codex.models")

CACHE_TTL = 600.0
EFFORT_ORDER = ("minimal", "low", "medium", "high", "xhigh", "max", "ultra")
EFFORT_LABELS = {
    "minimal": "Minimal",
    "low": "Low",
    "medium": "Medium",
    "high": "High",
    "xhigh": "Extra high",
    "max": "Max",
    "ultra": "Ultra",
}


def _rank(effort: str, default: int = 0) -> int:
    """Position in `EFFORT_ORDER`; ids Codex adds later sort at `default`."""
    return EFFORT_ORDER.index(effort) if effort in EFFORT_ORDER else default


@dataclass(slots=True)
class ModelCatalog:
    models: list[Choice] = field(default_factory=list)
    default_model: str | None = None
    efforts: list[Choice] = field(default_factory=list)
    default_effort: str | None = None
    per_model_efforts: dict[str, list[str]] = field(default_factory=dict)

    def clamp_effort(self, model: str | None, effort: str | None) -> str | None:
        """Codex does not validate effort at the RPC layer, so clamp it here."""
        if effort is None:
            return None
        supported = self.per_model_efforts.get(model or self.default_model or "")
        if not supported:
            supported = [choice.id for choice in self.efforts]
        if effort in supported:
            return effort
        wanted = _rank(effort, default=len(EFFORT_ORDER) - 1)
        ranked = sorted(supported, key=_rank)
        lower = [item for item in ranked if _rank(item) <= wanted]
        return lower[-1] if lower else (ranked[0] if ranked else None)


def parse_catalog(data: list[dict[str, Any]]) -> ModelCatalog:
    catalog = ModelCatalog()
    seen_efforts: set[str] = set()
    for entry in data[:256]:
        if entry.get("hidden"):
            continue
        model_id = str(entry.get("id") or entry.get("model") or "").strip()
        if not model_id:
            continue
        catalog.models.append(Choice(id=model_id, label=str(entry.get("displayName") or model_id)))
        efforts = [
            str(item.get("reasoningEffort"))
            for item in (entry.get("supportedReasoningEfforts") or [])[:16]
            if item.get("reasoningEffort")
        ]
        catalog.per_model_efforts[model_id] = efforts
        seen_efforts.update(efforts)
        if entry.get("isDefault"):
            catalog.default_model = model_id
            default_effort = entry.get("defaultReasoningEffort")
            catalog.default_effort = str(default_effort) if default_effort else None
    if catalog.default_model is None and catalog.models:
        catalog.default_model = catalog.models[0].id
    catalog.efforts = [
        Choice(id=item, label=EFFORT_LABELS.get(item, item.title()))
        for item in EFFORT_ORDER
        if item in seen_efforts
    ]
    return catalog


class CatalogCache:
    def __init__(self) -> None:
        self._value: ModelCatalog | None = None
        self._fetched_at = 0.0

    async def get(self, binary: str) -> ModelCatalog:
        if self._value is not None and time.monotonic() - self._fetched_at < CACHE_TTL:
            return self._value
        try:
            result = await one_shot(binary, "model/list", {})
            catalog = parse_catalog(list(result.get("data") or []))
        except Exception:
            log.warning("codex model/list failed; keeping the previous catalogue")
            return self._value or ModelCatalog()
        self._value = catalog
        self._fetched_at = time.monotonic()
        return catalog


catalog_cache = CatalogCache()
