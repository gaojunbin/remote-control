package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionState
import kotlin.test.Test

// `storeHook`, which watches a scripted turn on the demo gateway announce its end, arrives with the
// demo.

/**
 * `ios/Verification/AlertChecks.swift`: the two rules behind "tell me when the turn ends" and "don't
 * lock the screen on me". Both are pure, so both are checked here rather than on a device.
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
