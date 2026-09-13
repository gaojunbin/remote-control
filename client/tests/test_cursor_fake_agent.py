"""A fake `cursor-agent -p`: real process, real NDJSON, lines from the fixtures.

The runner spawns this through a shim exactly as it spawns Cursor, so the
subprocess, the line reader and the exit handling are all exercised. What it
prints comes from `tests/fixtures/cursor/`, written from Cursor's own docs and
from the module in its bundle that emits these lines.

`RC_FAKE_CURSOR_DIR` names a scratch directory: `mode` picks the scenario and
the arguments the runner passed are recorded in `argv.json`.

This module holds no tests; pytest collects it and finds none.
"""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "cursor"
SESSION_ID = "9f1c0f4e-1d1c-4a1e-9d6f-2f0f1f7c2c31"
# Enough lines to reach the first tool call, and no result.
SLOW_LINES = 9


def lines(name: str) -> list[str]:
    text = (FIXTURES / name).read_text(encoding="utf-8")
    return [line for line in text.splitlines() if line.strip()]


def emit(line: str) -> None:
    sys.stdout.write(line + "\n")
    sys.stdout.flush()


def main() -> int:
    directory = Path(os.environ["RC_FAKE_CURSOR_DIR"])
    (directory / "argv.json").write_text(json.dumps(sys.argv[1:]), encoding="utf-8")
    mode = (directory / "mode").read_text(encoding="utf-8").strip()
    rows = lines("turn.jsonl")
    if mode == "fail":
        emit(rows[0])
        return 2
    if mode == "slow":
        for row in rows[:SLOW_LINES]:
            emit(row)
        while True:
            time.sleep(0.05)
    for row in rows:
        emit(row)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (BrokenPipeError, KeyboardInterrupt):
        sys.exit(0)
