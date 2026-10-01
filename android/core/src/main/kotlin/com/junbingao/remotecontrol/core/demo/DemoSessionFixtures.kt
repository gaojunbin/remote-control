package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.demo.DemoFixtures.approvalSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.attachHintSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.ciDeviceID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.codexSharedSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.doneSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.erroredSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.grokSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.grokSharedSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.laptopDeviceID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.liveSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.macDeviceID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.now
import com.junbingao.remotecontrol.core.demo.DemoFixtures.outdatedBuild
import com.junbingao.remotecontrol.core.demo.DemoFixtures.outdatedClientVersion
import com.junbingao.remotecontrol.core.demo.DemoFixtures.pausedSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.piSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.revivedSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.servedBuild
import com.junbingao.remotecontrol.core.demo.DemoFixtures.servedClientVersion
import com.junbingao.remotecontrol.core.demo.DemoFixtures.sharedSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.terminalSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.updateFailure
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GitInfo
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SessionUsage
import com.junbingao.remotecontrol.core.protocol.TodoCounts
import com.junbingao.remotecontrol.core.protocol.TurnMarker

/** The demo's machines and the sessions they run, part of [DemoFixtures]. */
sealed interface DemoSessionFixtures {
    val devices: List<Device>
        get() = listOf(
            Device(deviceID = macDeviceID, name = "mac-studio-office", platform = DevicePlatform.macos,
                   hostname = "mac-studio.local", arch = "arm64",
                   clientVersion = servedClientVersion, clientBuild = servedBuild,
                   online = true, lastSeen = now, createdAt = now - 8_640_000, latencyMS = 18,
                   // Amendment A26: one machine with all four agents on it, so the picker, the
                   // card and a session of each can be seen.
                   agents = listOf(DemoFixtures.claude, DemoFixtures.codex, DemoFixtures.grok, DemoFixtures.pi),
                   // Amendment A38: the machine whose row opens a shell.
                   terminal = true),
            Device(deviceID = laptopDeviceID, name = "macbook-air", platform = DevicePlatform.macos,
                   hostname = "macbook-air.local", arch = "arm64",
                   clientVersion = outdatedClientVersion, clientBuild = outdatedBuild,
                   // The one machine the gateway could not bring to its wheel, so the notice and
                   // Retry update have somewhere to be seen.
                   updateState = DeviceUpdateState.failed, updateMessage = updateFailure,
                   online = true, lastSeen = now, createdAt = now - 4_320_000, latencyMS = 41,
                   // Amendment A28: the machine that is prepared for neither attachment, so both
                   // hints can be read on a real session.
                   agents = listOf(DemoFixtures.claudeWithoutShim, DemoFixtures.grokWithoutLeader),
                   // Amendment A38: the machine that turned the capability off, so the row's tap
                   // has a second thing to say.
                   terminal = false),
            Device(deviceID = ciDeviceID, name = "ci-runner-01", platform = DevicePlatform.linux,
                   hostname = "ci-runner-01", arch = "x86_64",
                   clientVersion = outdatedClientVersion, clientBuild = outdatedBuild,
                   online = false, lastSeen = now - 3_600_000, createdAt = now - 86_400_000,
                   // Amendment A38: it offers a terminal and is not there to open one, which is
                   // the third thing a row's tap can say.
                   latencyMS = null, agents = listOf(DemoFixtures.codexWithoutDaemon), terminal = true),
        )

    val sessions: List<Session>
        get() = listOf(
            Session(sessionID = liveSessionID, deviceID = macDeviceID, agent = "claude",
                    title = "Fix flaky auth test", cwd = "/Users/me/dev/remote-control/gateway",
                    git = GitInfo(branch = "main", dirty = true, ahead = 1),
                    state = SessionState.running, origin = EventSource.remote, control = SessionControl.remote,
                    model = "claude-sonnet-4-5", permissionMode = "acceptEdits", effort = "high",
                    createdAt = now - 600_000, updatedAt = now - 4_000, lastSeq = 0,
                    turn = TurnMarker(turnID = "demo-turn-1", startedAt = now - 252_000),
                    todos = TodoCounts(total = 4, done = 1),
                    usage = SessionUsage(inputTokens = 32_000, outputTokens = 16_200, totalTokens = 48_200,
                                         contextUsed = 61_000, contextWindow = 200_000, costUSD = 0.42)),
            Session(sessionID = approvalSessionID, deviceID = macDeviceID, agent = "codex",
                    title = "Migrate web to Vite 6", cwd = "/Users/me/dev/remote-control/web",
                    git = GitInfo(branch = "vite-6", dirty = true),
                    state = SessionState.needsApproval, origin = EventSource.terminal, control = SessionControl.remote,
                    model = "gpt-5.4-codex", permissionMode = "on-request",
                    createdAt = now - 1_800_000, updatedAt = now - 60_000, lastSeq = 0),
            // Amendment A7: a terminal session reports `running` while its turn runs and
            // `readonly` only when idle. Both are locked to the app. Amendment A17: the device read
            // all three settings out of the transcript, so the composer can show what this
            // terminal chose.
            Session(sessionID = terminalSessionID, deviceID = macDeviceID, agent = "claude",
                    title = "iOS push tokens", cwd = "/Users/me/dev/remote-control/ios",
                    git = GitInfo(branch = "main", dirty = false),
                    state = SessionState.running, origin = EventSource.terminal, control = SessionControl.terminal,
                    model = "claude-sonnet-4-5", permissionMode = "default", effort = "high",
                    createdAt = now - 10_800_000, updatedAt = now - 120_000, lastSeq = 0,
                    turn = TurnMarker(turnID = "demo-turn-terminal", startedAt = now - 120_000)),
            // Amendment A10: a CLI still owns this session, but the device is attached to it, so
            // the composer and approvals work as usual. Amendment A17: the channel carries no
            // `session.set`, so the three settings are shown rather than offered — and `auto` is a
            // permission mode the transcript has but the agent never lists, so the chip shows the
            // raw id.
            Session(sessionID = sharedSessionID, deviceID = macDeviceID, agent = "claude",
                    title = "Tidy the release notes", cwd = "/Users/me/dev/remote-control/docs",
                    git = GitInfo(branch = "main", dirty = true),
                    state = SessionState.idle, origin = EventSource.terminal, control = SessionControl.shared,
                    model = "claude-sonnet-4-5", permissionMode = "auto", effort = "high",
                    createdAt = now - 5_400_000, updatedAt = now - 90_000, lastSeq = 0),
            // Amendment A11: a Codex thread the terminal started, shared through the app-server
            // daemon. The attachment carries an interrupt, the thread settings and image inputs,
            // so nothing here is dimmed.
            Session(sessionID = codexSharedSessionID, deviceID = macDeviceID, agent = "codex",
                    title = "Typecheck the web app", cwd = "/Users/me/dev/remote-control/web",
                    git = GitInfo(branch = "feat/settings-drawer", dirty = true, ahead = 2),
                    state = SessionState.running, stateDetail = "Typed in the terminal",
                    origin = EventSource.terminal, control = SessionControl.shared,
                    model = "gpt-5.4-codex", permissionMode = "on-request", effort = "medium",
                    createdAt = now - 900_000, updatedAt = now - 12_000, lastSeq = 0,
                    turn = TurnMarker(turnID = "demo-turn-codex", startedAt = now - 42_000),
                    usage = SessionUsage(inputTokens = 14_980, outputTokens = 1_740, totalTokens = 16_720,
                                         contextUsed = 19_300, contextWindow = 272_000)),
            // Amendment A10: the same CLI on a machine without the shim. The app can only say how
            // to make the next run controllable.
            Session(sessionID = attachHintSessionID, deviceID = laptopDeviceID, agent = "claude",
                    title = "Rename the pairing flow", cwd = "/Users/me/dev/remote-control/client",
                    git = GitInfo(branch = "pairing", dirty = true),
                    state = SessionState.readonly, origin = EventSource.terminal, control = SessionControl.terminal,
                    model = "claude-sonnet-4-5", permissionMode = "default",
                    createdAt = now - 2_700_000, updatedAt = now - 300_000, lastSeq = 0),
            // The agent stopped on an error, and the machine is still there to say so: a red dot,
            // told apart from the grey of a session nothing owns any more.
            Session(sessionID = erroredSessionID, deviceID = macDeviceID, agent = "claude",
                    title = "Bump the Swift toolchain", cwd = "/Users/me/dev/remote-control/ios",
                    git = GitInfo(branch = "toolchain", dirty = true),
                    state = SessionState.error, stateDetail = "The agent exited before the build finished",
                    origin = EventSource.remote, control = SessionControl.remote,
                    model = "claude-sonnet-4-5", permissionMode = "default",
                    createdAt = now - 1_200_000, updatedAt = now - 30_000, lastSeq = 0),
            // Amendment A15: archived by hand and no longer owned, until a terminal attaches to it
            // again and the device clears the flag.
            Session(sessionID = revivedSessionID, deviceID = macDeviceID, agent = "claude",
                    title = "Draft the changelog", cwd = "/Users/me/dev/remote-control",
                    git = GitInfo(branch = "main"),
                    state = SessionState.stopped, origin = EventSource.terminal, control = SessionControl.none,
                    model = "claude-sonnet-4-5", permissionMode = "default",
                    createdAt = now - 9_000_000, updatedAt = now - 5_400_000, lastSeq = 0,
                    archived = true),
            // Amendment A25: Grok Build writes its own update log, so a session started in a
            // terminal is mirrored and read here. The summary carries the model and the effort but
            // never a permission mode, so the composer shows one chip where three would have stood
            // (A17). Amendment A28: this `grok` was started on the machine that leaves
            // `[cli] use_leader` off, so it runs its own backend and stays terminal-held however
            // long the app looks at it.
            Session(sessionID = grokSessionID, deviceID = laptopDeviceID, agent = "grok",
                    title = "Squash the pending migrations", cwd = "/Users/me/dev/remote-control/gateway",
                    git = GitInfo(branch = "migrations", dirty = true),
                    state = SessionState.readonly, origin = EventSource.terminal, control = SessionControl.terminal,
                    model = "grok-4.6", effort = "high",
                    createdAt = now - 3_000_000, updatedAt = now - 240_000, lastSeq = 0),
            // Amendment A28: a Grok session a terminal started inside the leader. The device
            // joined the same process, so the turn the TUI set off is stoppable from here and the
            // settings are live; only the attachment button is gone, because a prompt takes no
            // images.
            Session(sessionID = grokSharedSessionID, deviceID = macDeviceID, agent = "grok",
                    title = "Trim the gateway's retry budget",
                    cwd = "/Users/me/dev/remote-control/gateway",
                    git = GitInfo(branch = "retries", dirty = true, ahead = 1),
                    state = SessionState.running, stateDetail = "Typed in the terminal",
                    origin = EventSource.terminal, control = SessionControl.shared,
                    model = "grok-4.6", permissionMode = "default", effort = "high",
                    createdAt = now - 720_000, updatedAt = now - 8_000, lastSeq = 0,
                    turn = TurnMarker(turnID = "demo-turn-grok-shared", startedAt = now - 36_000)),
            // Amendment A26: pi's permission modes are the device's own, so the composer row
            // carries the model card and the permission chip.
            Session(sessionID = piSessionID, deviceID = macDeviceID, agent = "pi",
                    title = "Rewrite the config parser", cwd = "/Users/me/dev/remote-control/client",
                    git = GitInfo(branch = "config-parser", dirty = true, ahead = 3),
                    state = SessionState.idle, origin = EventSource.remote, control = SessionControl.remote,
                    model = "anthropic/claude-sonnet-4-5", permissionMode = "on-request",
                    effort = "medium",
                    createdAt = now - 1_200_000, updatedAt = now - 150_000, lastSeq = 0),
            // Amendment A35: stopped at the five-hour window with a resume pending. The dot stays
            // amber — the session is idle and the notice above the transcript is what carries the
            // pause.
            Session(sessionID = pausedSessionID, deviceID = macDeviceID, agent = "claude",
                    title = "Reindex the search corpus",
                    cwd = "/Users/me/dev/remote-control/gateway",
                    git = GitInfo(branch = "search-index", dirty = true),
                    state = SessionState.idle, origin = EventSource.terminal, control = SessionControl.shared,
                    model = "claude-sonnet-4-5", permissionMode = "acceptEdits", effort = "high",
                    createdAt = now - 21_600_000, updatedAt = now - 1_800_000, lastSeq = 0,
                    usage = SessionUsage(inputTokens = 128_400, outputTokens = 9_600,
                                         totalTokens = 138_000, contextUsed = 151_000,
                                         contextWindow = 200_000, costUSD = 1.84),
                    resume = DemoFixtures.pendingResume),
            Session(sessionID = doneSessionID, deviceID = ciDeviceID, agent = "codex",
                    title = "Add OTLP traces", cwd = "/work/api",
                    git = null, state = SessionState.idle, origin = EventSource.remote, control = SessionControl.none,
                    model = "gpt-5.4-codex", permissionMode = "never",
                    createdAt = now - 7_200_000, updatedAt = now - 3_600_000, lastSeq = 0),
        )
}
