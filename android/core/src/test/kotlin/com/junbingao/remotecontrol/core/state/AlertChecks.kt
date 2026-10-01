package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `ios/Verification/AlertChecks.swift`: the two rules behind "tell me when the turn ends" and "don't
 * lock the screen on me". Both are pure, so both are checked here rather than on a device, and so is
 * the hook the store tells the app of a transition through.
 */
class AlertChecks {
    /**
     * The table is `transition_kind()` in `gateway/rc_gateway/push.py`, and the app must read it the
     * same way or one channel would announce a turn the other stays quiet about.
     */
    @Test
    fun transitions() {
        val checks = CheckRunner("alerts")
        val table: List<Triple<SessionState, SessionState, PushKind?>> = listOf(
            Triple(SessionState.running, SessionState.idle, PushKind.turnCompleted),
            Triple(SessionState.needsApproval, SessionState.idle, PushKind.turnCompleted),
            Triple(SessionState.needsInput, SessionState.idle, PushKind.turnCompleted),
            Triple(SessionState.running, SessionState.needsApproval, PushKind.needsApproval),
            Triple(SessionState.running, SessionState.needsInput, PushKind.needsInput),
            Triple(SessionState.idle, SessionState.needsApproval, PushKind.needsApproval),
            Triple(SessionState.running, SessionState.error, PushKind.error),
            Triple(SessionState.idle, SessionState.error, PushKind.error),
            Triple(SessionState.starting, SessionState.running, null),
            Triple(SessionState.idle, SessionState.idle, null),
            Triple(SessionState.idle, SessionState.stopped, null),
            Triple(SessionState.idle, SessionState.running, null),
            Triple(SessionState.stopped, SessionState.idle, null),
            Triple(SessionState.readonly, SessionState.idle, null),
            Triple(SessionState.error, SessionState.error, null),
            Triple(SessionState.running, SessionState.readonly, null),
        )
        for ((previous, current, expected) in table) {
            checks.equal(TurnAlerts.kind(previous = previous, current = current), expected,
                         "${previous.rawValue} → ${current.rawValue}")
        }
        checks.assertAll()
    }

    /** A session the app is seeing for the first time did not just transition: the `hello` list is what it knows, not something that happened. */
    @Test
    fun firstSight() {
        val checks = CheckRunner("alerts")
        fun session(state: SessionState, id: String = "s1", device: String = "d1"): Session =
            Session(sessionID = id, deviceID = device, agent = "claude", title = "t", cwd = "/w", state = state)
        checks.expect(TurnAlerts.kind(previous = null, current = session(SessionState.idle)) == null,
                      "a session with nothing before it announces nothing")
        checks.equal(TurnAlerts.kind(previous = session(SessionState.running), current = session(SessionState.idle)),
                     PushKind.turnCompleted, "and one the app already held announces its finished turn")
        checks.expect(TurnAlerts.kind(previous = session(SessionState.running, id = "other"),
                                      current = session(SessionState.idle)) == null,
                      "two different sessions are not a transition")
        checks.expect(TurnAlerts.kind(previous = session(SessionState.running, device = "other"),
                                      current = session(SessionState.idle)) == null,
                      "and neither are two devices' sessions that share an id")
        checks.assertAll()
    }

    /**
     * Where the app learns of a transition: the store hands over both versions of a session it
     * already held, and nothing at all for the `hello` list.
     */
    @Test
    fun storeHook() = runTest {
        val checks = CheckRunner("alerts")
        val directory = scratchDirectory("alerts")
        try {
            val gateway = demoGateway(echoDelay = 50.milliseconds, resumeDelay = DemoGateway.defaultResumeDelay)
            val store = ConnectionStore(tasks = backgroundScope, cache = LocalCache(directory), makeAPI = { StubGateway(it) })
            val pairs = mutableListOf<Pair<Session, Session>>()
            store.onSessionTransition = { previous, current -> pairs.add(previous to current) }
            store.enterDemo(api = gateway, channel = gateway)
            settle { store.hasSnapshot }
            checks.equal(pairs.size, 0, "a hello is a list, not a transition")

            val idle = store.sessions.firstOrNull {
                it.state == SessionState.idle && it.control == SessionControl.remote && !it.archived
            }
            if (idle == null) {
                checks.expect(false, "the demo carries an idle session to type into")
            } else {
                val chat = ChatStore(session = idle, channel = gateway, tasks = backgroundScope)
                store.addFrameHandler("alerts") { frame -> chat.receive(frame) }
                chat.open()
                chat.draft = "run the suite again"
                chat.send()
                settle(timeout = 10.seconds) {
                    pairs.any { TurnAlerts.kind(previous = it.first, current = it.second) == PushKind.turnCompleted }
                }
                val kinds = pairs.filter { it.second.id == idle.id }
                    .mapNotNull { TurnAlerts.kind(previous = it.first, current = it.second) }
                checks.equal(kinds, listOf(PushKind.turnCompleted), "a scripted turn announces its end, once")
                checks.expect(pairs.all { it.first.id == it.second.id }, "and each pair is one session before and after")
                store.signOut()
            }
        } finally {
            directory.deleteRecursively()
        }
        checks.assertAll()
    }

    @Test
    fun awake() {
        val checks = CheckRunner("alerts")
        checks.expect(ScreenAwakeRule.awake(chatOnScreen = true, sceneActive = true),
                      "a conversation in the foreground holds the screen awake")
        checks.expect(!ScreenAwakeRule.awake(chatOnScreen = true, sceneActive = false),
                      "sending the app to the background gives the timer back")
        checks.expect(!ScreenAwakeRule.awake(chatOnScreen = false, sceneActive = true),
                      "the list and Settings leave the timer alone")
        checks.expect(!ScreenAwakeRule.awake(chatOnScreen = false, sceneActive = false),
                      "and so does a backgrounded app that is on neither")
        checks.assertAll()
    }
}
