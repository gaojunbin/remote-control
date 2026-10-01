package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Amendment A39: Close asks first only where something is lost. */
class SessionCloseTests {
    private fun session(state: SessionState, control: SessionControl = SessionControl.remote): Session =
        Session(sessionID = "s", deviceID = "d", agent = "claude", title = "Work", cwd = "/src", state = state,
                control = control)

    /** A turn under way is the one row that is asked about. */
    @Test
    fun working() {
        for (state in listOf(SessionState.running, SessionState.starting)) {
            assertTrue(SessionClose.asksFirst(session(state), online = true), "$state")
        }
    }

    /** Everything at rest, or waiting on the person, closes on the tap. */
    @Test
    fun quiet() {
        for (state in listOf(SessionState.idle, SessionState.readonly, SessionState.needsApproval,
                             SessionState.needsInput, SessionState.stopped, SessionState.error)) {
            assertFalse(SessionClose.asksFirst(session(state), online = true), "$state")
        }
    }

    /** A machine nobody can reach has no turn to lose. */
    @Test
    fun offline() {
        assertFalse(SessionClose.asksFirst(session(SessionState.running), online = false))
    }

    /** The question follows the dot, so the two can never disagree. */
    @Test
    fun followsTheDot() {
        for (state in listOf(SessionState.running, SessionState.starting, SessionState.idle, SessionState.readonly,
                             SessionState.needsApproval, SessionState.needsInput, SessionState.stopped,
                             SessionState.error)) {
            for (online in listOf(true, false)) {
                val row = session(state)
                assertEquals(row.dotTone(online = online) == DotTone.working, SessionClose.asksFirst(row, online = online),
                             "$state, online $online")
            }
        }
    }
}
