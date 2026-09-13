"""The models Cursor offers, and the labels an app shows for them.

`cursor-agent --list-models` is the only source, it needs a signed-in CLI, and
its layout is not documented, so the parser takes the first token of every line
that could be a model id and ignores everything else. When it answers nothing
the device advertises the set `fixtures/objects/agent.cursor.json` names.

`auto` always leads the list and is always the default: it is Cursor's own
"let the server choose", and the runner spells it by passing no `--model` at
all, so it is the one entry that is valid on every install.
"""

from __future__ import annotations

import re

from ...models import Choice
from . import runtime

AUTO = "auto"
MAX_MODELS = 64

# What a device that cannot ask offers, which is what `agent.cursor.json` says.
FALLBACK_MODELS = [
    Choice(AUTO, "Auto"),
    Choice("gpt-5", "GPT-5"),
    Choice("sonnet-4-thinking", "Sonnet 4 Thinking"),
]
# Ids whose everyday spelling a generic title-caser would get wrong.
KNOWN_LABELS = {AUTO: "Auto", "gpt-5": "GPT-5", "sonnet-4-thinking": "Sonnet 4 Thinking"}

_ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
_MODEL_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{1,63}\Z")
_BULLETS = " \t-*•>·"
# Words a heading or a prompt starts with, which are never model ids.
_NOISE = frozenset({"available", "models", "model", "error", "usage", "select", "current"})

_cache: dict[str, list[Choice]] = {}


def label_for(model_id: str) -> str:
    """`sonnet-4-thinking` reads as "Sonnet 4 Thinking" and anything new likewise."""
    known = KNOWN_LABELS.get(model_id)
    if known:
        return known
    parts = [part for part in re.split(r"[-_]", model_id) if part]
    return " ".join(part[:1].upper() + part[1:] for part in parts) or model_id


def parse_models(text: str) -> list[Choice]:
    """Every line whose first token could be a model id, in the order listed."""
    found: list[Choice] = []
    seen: set[str] = set()
    for raw in text.splitlines():
        line = _ANSI.sub("", raw).strip(_BULLETS).strip()
        if not line:
            continue
        token = line.split()[0]
        if token.lower() in _NOISE or not _MODEL_ID.match(token) or token in seen:
            continue
        seen.add(token)
        found.append(Choice(token, label_for(token)))
        if len(found) >= MAX_MODELS:
            break
    return found


def with_auto(models: list[Choice]) -> list[Choice]:
    rest = [choice for choice in models if choice.id != AUTO]
    return [Choice(AUTO, label_for(AUTO)), *rest]


async def load(binary: str | None) -> list[Choice]:
    """What this install offers, asked once per binary and then remembered.

    Detection runs whenever the device rescans its agents, and `--list-models`
    is a network call on a signed-in machine, so the answer is cached for the
    life of the process rather than fetched again on every scan.
    """
    if binary is None:
        return list(FALLBACK_MODELS)
    cached = _cache.get(binary)
    if cached is None:
        text = await runtime.probe_models(binary)
        listed = parse_models(text) if text else []
        cached = with_auto(listed) if listed else list(FALLBACK_MODELS)
        _cache[binary] = cached
    return list(cached)


def clear_cache() -> None:
    _cache.clear()
