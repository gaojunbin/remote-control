"""The `rc-client cursor …` commands, as `cli.py` calls them.

`hook` is started by Cursor before a tool call and never by a person; `setup`
registers that hook, `setup --remove` takes it out again, and `status` prints
what is registered without changing anything.
"""

from __future__ import annotations

from . import hook, setup

ACTIONS = ("setup", "hook", "status")


def run_hook() -> int:
    """Exit 0 whatever happens: Cursor reads a failure as the person's problem."""
    return hook.main()


def run_setup(remove: bool = False) -> int:
    for line in setup.remove() if remove else setup.install():
        print(line)
    return 0


def run_status() -> int:
    for line in setup.status():
        print(line)
    return 0


def run(action: str, *, remove: bool = False) -> int:
    if action == "hook":
        return run_hook()
    if action == "status":
        return run_status()
    return run_setup(remove)
