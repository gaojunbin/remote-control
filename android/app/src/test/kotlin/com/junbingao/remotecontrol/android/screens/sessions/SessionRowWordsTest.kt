package com.junbingao.remotecontrol.android.screens.sessions

import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ios/VerificationUI` § "The session row says where it came from": the word beside the dot is the
 * origin and never the state, whatever the session is doing, so the row and the dot cannot
 * contradict each other.
 */
class SessionRowWordsTest {
    @After
    fun englishAgain() = L10n.use(L10n.english)

    private val everyState = listOf(
        SessionState.starting, SessionState.running, SessionState.needsApproval, SessionState.needsInput,
        SessionState.idle, SessionState.readonly, SessionState.stopped, SessionState.error,
    )

    private fun sample(origin: EventSource, state: SessionState) = Session(
        sessionID = "s", deviceID = "d", agent = "claude", title = "Work", cwd = "/src",
        state = state, origin = origin, control = SessionControl.remote,
    )

    @Test
    fun theWordIsTheOriginInEveryState() {
        assertTrue(
            "a session a terminal started reads Terminal in every state it can be in",
            everyState.all { sample(EventSource.terminal, it).originLabel == "Terminal" },
        )
        assertTrue(
            "and one started from a phone or a browser reads Remote Control in every state",
            everyState.all { sample(EventSource.remote, it).originLabel == "Remote Control" },
        )
    }

    @Test
    fun theDemoListCarriesBothOriginsAndNoStateWord() {
        val words = DemoFixtures.sessions.map { it.originLabel }.toSet()
        assertEquals("the demo list carries both origins", setOf("Terminal", "Remote Control"), words)
        // The chat header's state words (`SessionState.label`, and the attached session's).
        val states = setOf("starting", "running", "needs approval", "needs input", "error", "stopped", "terminal", "done", "terminal · attached")
        assertTrue("and no row says what the session is doing", words.none { it in states })
    }

    @Test
    fun theOriginIsSaidInTheInterfaceLanguage() {
        L10n.use(L10n.chinese)
        assertEquals("终端", sample(EventSource.terminal, SessionState.idle).originLabel)
        assertEquals("远程启动", sample(EventSource.remote, SessionState.idle).originLabel)
    }

    @Test
    fun aPickerIsTitledWithTheFolderItStandsIn() {
        assertEquals("dev", lastPathComponent("/Users/me/dev"))
        assertEquals("round-41", lastPathComponent("/Users/me/dev/round-41/"))
        assertEquals("the root is itself", "/", lastPathComponent("/"))
    }
}
