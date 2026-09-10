"""The daemon: agent discovery, the gateway link, the session hub and mirroring."""

from __future__ import annotations

import asyncio
import contextlib
import platform
import signal
import socket
from typing import Any

from . import config as config_module
from .agents.discovery import detect_agents
from .child_env import scrub_parent_secrets
from .config import Config
from .errors import RcError
from .fs import list_dirs, merge_recents
from .gateway import GatewayLink
from .git import git_info
from .logging_setup import logger
from .models import AgentInfo
from .registry import Registry
from .sessions.hub import SessionHub
from .sessions.mirror import MirrorService

log = logger("rc_client.daemon")

AGENT_REFRESH_INTERVAL = 900.0


class Daemon:
    def __init__(self, config: Config) -> None:
        self.config = config
        self.registry = Registry(config_module.database_path())
        self.agents: list[AgentInfo] = []
        self.hub = SessionHub(self.registry, self._publish, config.device_id, lambda: self.agents)
        self.mirror = MirrorService(self.hub, config.mirror)
        self.link = GatewayLink(
            config.device_ws_url,
            config.device_token,
            hello=self._hello,
            handlers=self._handlers(),
            on_ready=self._on_ready,
        )
        self._refresh_task: asyncio.Task[None] | None = None

    # ------------------------------------------------------------- lifecycle

    async def run(self) -> None:
        scrub_parent_secrets()
        self.agents = await detect_agents()
        self.hub.load()
        log.info(
            "device daemon starting",
            device=self.config.device_id,
            agents=",".join(info.agent for info in self.agents if info.available),
        )
        self.link.start()
        self.mirror.start()
        self._refresh_task = asyncio.create_task(self._refresh_loop())
        try:
            await self._wait_for_stop()
        finally:
            await self.shutdown()

    async def _wait_for_stop(self) -> None:
        """Block until the process is asked to stop, so shutdown actually runs."""
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        installed: list[signal.Signals] = []
        for signal_number in (signal.SIGTERM, signal.SIGINT):
            try:
                loop.add_signal_handler(signal_number, stop.set)
            except (NotImplementedError, RuntimeError, ValueError):
                continue
            installed.append(signal_number)
        try:
            await stop.wait()
        finally:
            for signal_number in installed:
                with contextlib.suppress(NotImplementedError, RuntimeError, ValueError):
                    loop.remove_signal_handler(signal_number)

    async def shutdown(self) -> None:
        if self._refresh_task is not None:
            self._refresh_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._refresh_task
            self._refresh_task = None
        await self.mirror.stop()
        await self.hub.close()
        await self.link.stop()
        self.registry.close()

    async def _refresh_loop(self) -> None:
        while True:
            await asyncio.sleep(AGENT_REFRESH_INTERVAL)
            try:
                agents = await detect_agents()
            except Exception:
                log.exception("agent re-detection failed")
                continue
            if [info.to_dict() for info in agents] != [info.to_dict() for info in self.agents]:
                self.agents = agents
                await self._publish(
                    {"type": "agents.updated", "agents": [info.to_dict() for info in agents]}
                )

    async def _publish(self, frame: dict[str, Any]) -> None:
        await self.link.send(frame)

    async def _on_ready(self) -> None:
        await self.mirror.scan_once()

    async def _hello(self) -> dict[str, Any]:
        return {
            "name": self.config.name or socket.gethostname(),
            "platform": "macos" if platform.system() == "Darwin" else "linux",
            "hostname": socket.gethostname(),
            "arch": "arm64" if platform.machine() in {"arm64", "aarch64"} else "x86_64",
            "agents": [info.to_dict() for info in self.agents],
            "sessions": self.hub.snapshot(),
        }

    # -------------------------------------------------------------- handlers

    def _handlers(self) -> dict[str, Any]:
        return {
            "session.create": self.hub.create,
            "session.send": self.hub.send,
            "session.stop": self.hub.stop,
            "session.approve": self.hub.approve,
            "session.answer": self.hub.answer,
            "session.set": self.hub.set_options,
            "session.history": self.hub.history,
            "session.block": self.hub.block,
            "session.queue_remove": self.hub.queue_remove,
            "session.takeover": self.hub.takeover,
            "session.archive": self.hub.archive,
            "session.delete": self.hub.delete,
            "device.dirs": self._device_dirs,
            "device.git": self._device_git,
            "device.agents": self._device_agents,
        }

    async def _device_dirs(self, params: dict[str, Any]) -> dict[str, Any]:
        recents = merge_recents(self._session_recents())
        return await asyncio.to_thread(list_dirs, params.get("path"), recents)

    def _session_recents(self) -> list[tuple[str, int]]:
        return [
            (entry.session.cwd, entry.session.updated_at)
            for entry in self.hub.entries.values()
            if entry.session.cwd
        ]

    async def _device_git(self, params: dict[str, Any]) -> dict[str, Any]:
        path = str(params.get("path") or "")
        if not path:
            raise RcError("bad_request", "path is required")
        return await git_info(path)

    async def _device_agents(self, params: dict[str, Any]) -> dict[str, Any]:
        self.agents = await detect_agents()
        return {"agents": [info.to_dict() for info in self.agents]}
