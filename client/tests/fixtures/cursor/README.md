# Cursor `stream-json` fixtures

These lines were **not** recorded from a live run. `cursor-agent` is installed on the development
machine but not signed in, so no turn could be executed. Each line was written from two sources:

- Cursor's published output-format documentation (`cursor.com/docs/cli/reference/output-format`),
  which gives the `system`/`init`, `user`, `assistant`, `tool_call` and `result` envelopes.
- The shipped bundle of `cursor-agent 2026.09.02-c22c1a3`, read at
  `~/.local/share/cursor-agent/versions/<version>/4347.index.js`, which is the module that writes
  every one of these lines. It is the authority for the fields the docs do not list: the `thinking`
  events, the `usage` object on `result`, and the way a buffered text flush is distinguished from a
  delta.

Treat every shape here as documented, not verified. The first live run on a signed-in machine
should be diffed against these files.
