"""`device.dirs`: a browsable, directory-only view of the device filesystem."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .errors import RcError

MAX_ENTRIES = 400


def _is_git_repo(path: Path) -> bool:
    return (path / ".git").exists()


def list_dirs(path: str | None, recents: list[tuple[str, int]]) -> dict[str, Any]:
    """List sub-directories of `path` (home when omitted), hidden ones excluded."""
    target = Path(path).expanduser() if path else Path.home()
    try:
        target = target.resolve()
    except OSError as exc:
        raise RcError("not_found", f"cannot resolve {target}") from exc
    if not target.is_dir():
        raise RcError("not_found", f"not a directory: {target}")

    entries: list[dict[str, Any]] = []
    try:
        with_names = sorted(target.iterdir(), key=lambda item: item.name.lower())
    except PermissionError as exc:
        raise RcError("forbidden", f"permission denied: {target}") from exc
    for child in with_names:
        if child.name.startswith("."):
            continue
        try:
            if not child.is_dir():
                continue
        except OSError:
            continue
        entries.append({"name": child.name, "path": str(child), "is_git": _is_git_repo(child)})
        if len(entries) >= MAX_ENTRIES:
            break

    parent = str(target.parent) if target.parent != target else None
    return {
        "path": str(target),
        "parent": parent,
        "entries": entries,
        "recent": [{"path": item, "last_used": used} for item, used in recents],
    }


def merge_recents(*sources: list[tuple[str, int]]) -> list[tuple[str, int]]:
    """Collapse recent-directory candidates, newest use wins, capped at 20."""
    best: dict[str, int] = {}
    for source in sources:
        for path, used in source:
            if not path:
                continue
            if used > best.get(path, 0):
                best[path] = used
    ordered = sorted(best.items(), key=lambda item: item[1], reverse=True)
    return ordered[:20]
