"""Shared object projections (protocol §3).

One place builds the ``Device`` object so the HTTP list, the app ``hello`` and every
``device.updated`` push agree field for field. The agent inventory is read from the persisted
device record, which the hub refreshes on every hello and ``agents.updated``, so the list renders
the same whether the device is online or asleep.
"""

from __future__ import annotations

from typing import Any

from .devices import DeviceRecord

Device = dict[str, Any]


def device_view(
    record: DeviceRecord,
    *,
    online: bool,
    latency_ms: int | None = None,
    last_seen: int | None = None,
) -> Device:
    return {
        "device_id": record.device_id,
        "name": record.name,
        "platform": record.platform,
        "hostname": record.hostname,
        "arch": record.arch,
        "client_version": record.client_version,
        "online": online,
        "last_seen": last_seen if last_seen is not None else _millis(record.last_seen),
        "created_at": record.created_at * 1000,
        "latency_ms": latency_ms,
        "agents": record.agents,
    }


def has_available_agent(agents: list[dict[str, Any]] | None) -> bool:
    return any(isinstance(agent, dict) and agent.get("available") for agent in agents or [])


def _millis(seconds: int | None) -> int | None:
    return None if seconds is None else seconds * 1000
