package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The dot on a session row, in the chat header and anywhere else it appears. The rule reads all
 * three facts, because `state` alone cannot tell a finished turn on a live session from one whose
 * CLI exited: both report `idle`.
 */
class StatusDotTests {
    private val owners = listOf(SessionControl.remote, SessionControl.terminal, SessionControl.shared, SessionControl.none)

    /** A turn under way is green, whoever started it. */
    @Test
    fun working() {
        for (control in owners) {
            assertEquals(DotTone.working, DotTone.of(state = SessionState.starting, control = control, online = true))
            assertEquals(DotTone.working, DotTone.of(state = SessionState.running, control = control, online = true))
        }
    }

    /** A session blocked on the user is amber, and the only tone that moves. */
    @Test
    fun waiting() {
        for (control in owners) {
            assertEquals(DotTone.waiting, DotTone.of(state = SessionState.needsApproval, control = control, online = true))
            assertEquals(DotTone.waiting, DotTone.of(state = SessionState.needsInput, control = control, online = true))
        }
    }

    /** Quiet and still owned is amber; quiet and owned by nothing is grey. */
    @Test
    fun liveAndExited() {
        for (state in listOf(SessionState.idle, SessionState.readonly)) {
            assertEquals(DotTone.live, DotTone.of(state = state, control = SessionControl.remote, online = true))
            assertEquals(DotTone.live, DotTone.of(state = state, control = SessionControl.terminal, online = true))
            assertEquals(DotTone.live, DotTone.of(state = state, control = SessionControl.shared, online = true))
            assertEquals(DotTone.off, DotTone.of(state = state, control = SessionControl.none, online = true))
        }
    }

    /** An error is red, and a stopped session is grey. */
    @Test
    fun failedAndStopped() {
        for (control in owners) {
            assertEquals(DotTone.failed, DotTone.of(state = SessionState.error, control = control, online = true))
            assertEquals(DotTone.off, DotTone.of(state = SessionState.stopped, control = control, online = true))
        }
    }

    /** A machine that cannot be reached reports nothing, whatever it last said. */
    @Test
    fun offline() {
        for (state in listOf(SessionState.starting, SessionState.running, SessionState.needsApproval,
                             SessionState.needsInput, SessionState.idle, SessionState.readonly, SessionState.stopped,
                             SessionState.error)) {
            for (control in owners) {
                assertEquals(DotTone.off, DotTone.of(state = state, control = control, online = false), "$state, $control")
            }
        }
    }

    /** A state this build has never heard of claims nothing. */
    @Test
    fun unknownState() {
        assertEquals(DotTone.off, DotTone.of(state = SessionState("compacting"), control = SessionControl.remote, online = true))
    }

    /** A session reads its own tone from the device it runs on. */
    @Test
    fun fromASession() {
        val session = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "Work", cwd = "/src",
                              state = SessionState.idle, control = SessionControl.shared)
        assertEquals(DotTone.live, session.dotTone(online = true))
        assertEquals(DotTone.off, session.dotTone(online = false))
    }
}

/** The key to the colours the Sessions screen draws above its list. */
class DotLegendTests {
    /** Four entries, in the order they are read. */
    @Test
    fun order() {
        assertEquals(listOf("Working", "For you", "Not running", "Error"), DotLegend.entries.map { it.text })
    }

    /** One entry per colour, and the amber one is the still one. */
    @Test
    fun tones() {
        assertEquals(listOf(DotTone.working, DotTone.live, DotTone.off, DotTone.failed), DotLegend.entries.map { it.tone })
        assertFalse(DotLegend.entries.any { it.tone == DotTone.waiting })
    }

    /** Every tone the dot can take is spoken for. */
    @Test
    fun everyToneIsCovered() {
        val spoken = DotLegend.entries.map { it.tone }.toSet() + DotTone.waiting
        assertEquals(DotTone.allCases.toSet(), spoken)
    }
}
