# Documentation

Start with the [root README](../README.md) for what remote-control is and how to get it running.
These documents go deeper.

| Document | Read it when you want to |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Understand the components, the data flow, the block timeline, how a terminal session is attached through the Claude channel or the shared Codex daemon, where state lives, and why the boundaries fall where they do |
| [DEPLOY.md](DEPLOY.md) | Deploy or operate the gateway: every `.env` variable, TLS, upgrades, backups, speech-to-text, push, troubleshooting |
| [CLIENT.md](CLIENT.md) | Install, run or debug the device daemon: the installer, `rc-client` commands, services, agent discovery, terminal mirroring, the `claude` shim, the shared Codex daemon and attached sessions |
| [WEB.md](WEB.md) | Build, run or change the browser app, including its mock gateway |
| [IOS.md](IOS.md) | Build, run or ship the iPhone app, including the TestFlight prerequisites |
| [DESIGN.md](DESIGN.md) | Change the interface: the prototype screens, composer semantics, approval rules, status vocabulary, design tokens |
| [VALIDATION.md](VALIDATION.md) | Know what was tested end to end on the gateway and the device daemon, what broke and was fixed, and what was never verified |
| [VALIDATION-APPS.md](VALIDATION-APPS.md) | The same for the web and iOS apps, driven against a real gateway, a real device and the real CLIs |

The normative wire contract lives outside `docs/`, next to its schema and fixtures:
[`protocol/PROTOCOL.md`](../protocol/PROTOCOL.md). Each component also carries a README with its own
layout and commands: [`gateway/`](../gateway/README.md), [`client/`](../client/README.md),
[`web/`](../web/README.md), [`ios/`](../ios/README.md), [`protocol/`](../protocol/README.md).
