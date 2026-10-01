package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AccountRules
import com.junbingao.remotecontrol.core.protocol.AttachmentInfo
import com.junbingao.remotecontrol.core.state.AppBuild

/**
 * Typed fixtures for the offline demo and for previews. They mirror the examples in the frozen
 * protocol document.
 *
 * RCCore's is one long file. Here the identifiers and the plain values stay in this object, and
 * the rest is split by subject into the sealed interfaces it implements — the agents, their
 * quotas, the slash commands, the machines and their sessions, their transcripts, and what the
 * gateway says about itself — so every fixture is still read as `DemoFixtures.claude`.
 */
object DemoFixtures :
    DemoAgentFixtures,
    DemoQuotaFixtures,
    DemoCommandFixtures,
    DemoSessionFixtures,
    DemoHistoryFixtures,
    DemoTerminalHistoryFixtures,
    DemoGatewayFixtures {
    const val macDeviceID = "demo-mac-studio"
    const val ciDeviceID = "demo-ci-runner"
    const val laptopDeviceID = "demo-macbook-air"
    const val liveSessionID = "demo-session-auth"
    const val approvalSessionID = "demo-session-vite"
    const val doneSessionID = "demo-session-otlp"

    /** A session a terminal owns outright, which the app can only read. */
    const val terminalSessionID = "demo-session-push"

    /** Amendment A10: a terminal session this device is attached to. */
    const val sharedSessionID = "demo-session-shared"

    /**
     * Amendment A10: a terminal session on a device whose shim is not installed, so the app can
     * only explain how to make it controllable.
     */
    const val attachHintSessionID = "demo-session-rename"

    /**
     * Amendment A11: a Codex thread shared through the app-server daemon. The attachment carries
     * settings, attachments and an interrupt, so the app drives it as fully as one it started
     * itself.
     */
    const val codexSharedSessionID = "demo-session-typecheck"

    /**
     * A turn that ended badly on a machine that is still reachable, so the list carries the red
     * dot as well as the other four.
     */
    const val erroredSessionID = "demo-session-toolchain"

    /**
     * Amendment A15: a session the user archived by hand, which the demo device brings back to
     * life shortly after the list opens.
     */
    const val revivedSessionID = "demo-session-changelog"

    /**
     * Amendment A25: a Grok Build session a terminal started, mirrored from the update log Grok
     * keeps, so the app reads it and cannot write to it. Amendment A28: it runs on the machine
     * whose Grok is configured without the leader, which is the only way a Grok session is still
     * terminal-held.
     */
    const val grokSessionID = "demo-session-migrations"

    /**
     * Amendment A28: a Grok Build session a terminal started inside the leader, which the device
     * joined as another client of the same process. The leader relays an interrupt and the
     * session settings but takes no images, so the composer keeps every control except the
     * attachment.
     */
    const val grokSharedSessionID = "demo-session-retries"

    /**
     * Amendment A26: a pi session. pi's permission modes are the device's own, enforced by the
     * extension it loads, so the composer row carries the permission chip exactly as Codex's does.
     */
    const val piSessionID = "demo-session-parser"

    /**
     * Amendment A35: a session the five-hour window stopped, with a resume the device scheduled
     * for a minute after the window resets. It is attached, which is the case that can be resumed
     * and the case that can be dropped.
     */
    const val pausedSessionID = "demo-session-indexer"

    val now: Long get() = System.currentTimeMillis()

    /** When this demo device says it read the windows: the moment it answers. */
    internal val checkedNow: Long get() = now

    // Builds (A22, A36)

    /**
     * Amendment A22: the build this demo gateway serves, and the older one the laptop is still on
     * so a machine the gateway could not bring forward can be looked at (A36).
     */
    const val servedBuild = "3f2b4a9c1d8e7f60a5b4c3d2e1f0918273645a5b6c7d8e9f0a1b2c3d4e5f6a7b"
    const val outdatedBuild = "9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9e8d"

    /**
     * The versions those two builds are, so the demo's rows and its Update confirmation name a
     * client the way a real gateway would. A round ships every component on one version, so what
     * the demo gateway serves is whatever this app is: the demo cannot fall a round behind.
     */
    val servedClientVersion: String get() = AppBuild.version
    const val outdatedClientVersion = "1.3.0"

    /**
     * Why the gateway's own attempt on the laptop did not finish (A36). It is the message the
     * gateway keeps when a device never comes back, and it is what puts Retry update on that one
     * row.
     */
    const val updateFailure = "the device did not come back"

    // Pairing (A23)

    const val claimToken = "7ZK3M9Q2X5H8B1V4N6P0R2T4W6"

    const val claimURL = "https://demo.remote-control.invalid/pair#$claimToken"

    // Accounts (A24)

    /**
     * The demo signs in as the gateway's own operator, so every screen an admin has — including
     * the Users screen, where this row is the one with no actions on it — is reachable from
     * `--demo`.
     */
    const val adminUsername = AccountRules.operatorUsername

    /** A member, so signing in as one shows the Settings group without the Users row and the account routes answering `403`. */
    const val memberUsername = "alice"

    /** A disabled account, so the sign-in form's `403` can be read. */
    const val disabledUsername = "bob"

    // The queue (A43)

    /**
     * Amendment A43: what `--demo-queue` holds behind the live session's turn, in the order it
     * will go. The last one carries two files, so its row can be removed and not edited.
     */
    internal fun heldMessages(base: Long = now): List<DemoQueue.Item> = listOf(
        DemoQueue.Item(id = "demo-queued-suite", text = "Then run the full test suite.", ts = base - 180_000),
        DemoQueue.Item(id = "demo-queued-regression", text = "Add a regression test for the refresh race.",
                       ts = base - 120_000),
        DemoQueue.Item(id = "demo-queued-evidence", text = "Here are the CI log and a screenshot of the failing run.",
                       ts = base - 60_000,
                       files = listOf(AttachmentInfo(name = "ci-log.txt", mime = "text/plain", size = 18_204),
                                      AttachmentInfo(name = "failing-run.png", mime = "image/png", size = 284_913))),
    )
}
