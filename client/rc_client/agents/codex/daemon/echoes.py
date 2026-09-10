"""Recognising the daemon's echo of a prompt this device sent.

Codex replays every prompt as a `userMessage` item, twice: once on
`item/started` and again on `item/completed`, with the same item id both times.
Neither carries anything that names its author on the way back — the observed
`clientId` of our own injection is `null` — so the text we sent is the only
correlation key, and the item id is what keeps the second event, a backfill and
a reconnect from re-publishing a message the apps already show.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field

# How many of our own prompts to keep around so the daemon's echo of them can be
# recognised; a turn only ever echoes the message that started it.
PENDING = 8
# Item ids already recognised as ours. Larger than `PENDING` because a backfill
# replays items long after their echo was consumed.
RECOGNISED = 64


@dataclass(slots=True)
class EchoLog:
    """The prompts this device sent that the daemon has yet to echo back."""

    pending: deque[str] = field(default_factory=lambda: deque(maxlen=PENDING))
    items: deque[str] = field(default_factory=lambda: deque(maxlen=RECOGNISED))
    clients: set[str] = field(default_factory=set)

    def __bool__(self) -> bool:
        return bool(self.pending)

    def remember(self, text: str) -> None:
        """Record a prompt we just sent, so its echo can be dropped."""
        self.pending.append(text)

    def owns(self, client_id: str) -> bool:
        """Whether this `clientId` is one the daemon has stamped on our own echoes."""
        return bool(client_id) and client_id in self.clients

    def recognised(self, item_id: str) -> bool:
        """Whether this item is an echo we have already matched."""
        return bool(item_id) and item_id in self.items

    def claim(self, item_id: str, text: str, client_id: str) -> bool:
        """Consume one pending echo matching `text`, if there is one.

        Only the first match consumes it, so a terminal user typing the same
        words as the message we just sent still gets their own bubble: the echo
        is spent on ours, and theirs arrives as news.
        """
        if text not in self.pending:
            return False
        self.pending.remove(text)
        if item_id:
            self.items.append(item_id)
        if client_id:
            self.clients.add(client_id)
        return True
