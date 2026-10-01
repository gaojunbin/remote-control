package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the app tells someone their turn ended, and when it says nothing.
 *
 * The table is the gateway's: `transition_kind()` in `gateway/rc_gateway/push.py`. A banner the app
 * raises for itself and a push the gateway sends have to mean the same thing, or one channel would
 * announce a turn the other stayed quiet about.
 */
class TurnAlertTests {
    /** A turn that finishes is announced, from every state a turn can be in. */
    @Test
    fun finished() {
        for (previous in listOf(SessionState.running, SessionState.needsApproval, SessionState.needsInput)) {
            assertEquals(PushKind.turnCompleted, TurnAlerts.kind(previous = previous, current = SessionState.idle), "$previous")
        }
    }

    /** A session blocked on the user is announced as what it is waiting for. */
    @Test
    fun blocked() {
        assertEquals(PushKind.needsApproval, TurnAlerts.kind(previous = SessionState.running, current = SessionState.needsApproval))
        assertEquals(PushKind.needsInput, TurnAlerts.kind(previous = SessionState.running, current = SessionState.needsInput))
        assertEquals(PushKind.needsApproval, TurnAlerts.kind(previous = SessionState.idle, current = SessionState.needsApproval))
        assertEquals(PushKind.needsApproval, TurnAlerts.kind(previous = SessionState.needsInput, current = SessionState.needsApproval))
    }

    /** An error is announced from wherever it arrived. */
    @Test
    fun errored() {
        for (previous in listOf(SessionState.starting, SessionState.running, SessionState.idle, SessionState.needsApproval,
                                SessionState.stopped)) {
            assertEquals(PushKind.error, TurnAlerts.kind(previous = previous, current = SessionState.error), "$previous")
        }
    }

    /** Nothing is announced for a session that was not working, or still is. */
    @Test
    fun silent() {
        assertNull(TurnAlerts.kind(previous = SessionState.starting, current = SessionState.running))
        assertNull(TurnAlerts.kind(previous = SessionState.idle, current = SessionState.running))
        assertNull(TurnAlerts.kind(previous = SessionState.idle, current = SessionState.idle))
        assertNull(TurnAlerts.kind(previous = SessionState.idle, current = SessionState.stopped))
        assertNull(TurnAlerts.kind(previous = SessionState.running, current = SessionState.stopped))
        assertNull(TurnAlerts.kind(previous = SessionState.error, current = SessionState.error))
        // A CLI that exited reports `idle` too, and it never ran a turn here.
        assertNull(TurnAlerts.kind(previous = SessionState.stopped, current = SessionState.idle))
        assertNull(TurnAlerts.kind(previous = SessionState.readonly, current = SessionState.idle))
    }

    /** A session seen for the first time announces nothing. */
    @Test
    fun firstSight() {
        assertNull(TurnAlerts.kind(previous = null, current = session(SessionState.idle)))
        assertNull(TurnAlerts.kind(previous = null, current = session(SessionState.error)))
        assertEquals(PushKind.turnCompleted,
                     TurnAlerts.kind(previous = session(SessionState.running), current = session(SessionState.idle)))
    }

    /** Sessions are keyed by device and id together, so two of them are never one transition. */
    @Test
    fun keyedByDeviceAndSession() {
        assertNull(TurnAlerts.kind(previous = session(SessionState.running, id = "other"), current = session(SessionState.idle)))
        assertNull(TurnAlerts.kind(previous = session(SessionState.running, device = "other"), current = session(SessionState.idle)))
    }

    private fun session(state: SessionState, id: String = "s1", device: String = "d1"): Session =
        Session(sessionID = id, deviceID = device, agent = "claude", title = "Fix the flake", cwd = "/w", state = state)
}

/** The idle timer is held off in a conversation and nowhere else. */
class ScreenAwakeTests {
    /** A conversation in the foreground holds the screen. */
    @Test
    fun inConversation() {
        assertTrue(ScreenAwakeRule.awake(chatOnScreen = true, sceneActive = true))
    }

    /** Leaving the conversation, or the foreground, gives the timer straight back. */
    @Test
    fun everywhereElse() {
        assertFalse(ScreenAwakeRule.awake(chatOnScreen = true, sceneActive = false))
        assertFalse(ScreenAwakeRule.awake(chatOnScreen = false, sceneActive = true))
        assertFalse(ScreenAwakeRule.awake(chatOnScreen = false, sceneActive = false))
    }
}
