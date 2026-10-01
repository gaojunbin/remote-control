package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentOption
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The names and the decoding of RCCore's suite of this name are in `protocol/AgentsTests.kt`, and
// the cases that read only the demo's devices and sessions — `demoDeviceCarriesFour`,
// `commandCapability`, `demoSessions`, `grokSessionsFollowTheLeader`, `noCursorSessionSurvives` —
// are in `demo/AgentsTests.kt`.

/** Amendment A25: what an agent's lists give the composer and the list, whoever the agent is. */
class AgentsTests {
    // What an empty list takes away

    /**
     * pi now shows its permission chip, and its picker. Amendment A26: pi's modes are the device's
     * own, so the chip an earlier round drew nothing for is now drawn exactly as Codex's is — and by
     * the same code, with nothing in the app to change.
     */
    @Test
    fun piShowsThePermissionControl() {
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.piSessionID })
        val chips = TerminalSetting.all(session, agent = DemoFixtures.pi)
        assertEquals(listOf("modelCard", "permissionMode"), chips.map { it.id })
        assertEquals(listOf("Claude Sonnet 4.5 Medium", "Ask when needed"), chips.map { it.text })
        assertEquals("Ask when needed", TerminalSetting.permissionText(session, agent = DemoFixtures.pi))
        assertFalse(DemoFixtures.pi.permissionModes.isEmpty(),
                    "which is what the new-session form reads before it draws its Permissions row")
    }

    /** An agent with no permission system still shows no chip. Amendment A17 is unchanged: an agent that lists no modes still draws no chip, whatever the session reports. */
    @Test
    fun anEmptyListStillTakesTheChipAway() {
        val bare = AgentInfo(agent = "aider", available = true, models = listOf(AgentOption(id = "m", label = "M")),
                             defaultModel = "m")
        val session = Session(sessionID = "s", deviceID = "d", agent = "aider", title = "Port", cwd = "/tmp",
                              control = SessionControl.terminal, model = "m", permissionMode = "default", updatedAt = 0)
        assertNull(TerminalSetting.permissionText(session, agent = bare))
        assertEquals(listOf("modelCard"), TerminalSetting.all(session, agent = bare).map { it.id })
    }

    /** An agent with no effort levels reads the model alone. */
    @Test
    fun anAgentWithoutEffortsReadsTheModelAlone() {
        val bare = AgentInfo(agent = "aider", available = true, models = listOf(AgentOption(id = "auto", label = "Auto")),
                             defaultModel = "auto",
                             permissionModes = listOf(AgentOption(id = "default", label = "Ask when needed")),
                             defaultPermissionMode = "default")
        val session = Session(sessionID = "s", deviceID = "d", agent = "aider", title = "Storybook", cwd = "/tmp",
                              control = SessionControl.terminal, model = "auto", permissionMode = "default",
                              effort = "high", updatedAt = 0)
        assertNull(TerminalSetting.effortText(session, agent = bare))
        assertEquals("Auto", TerminalSetting.modelCardText(session, agent = bare))
        assertEquals(listOf("Auto", "Ask when needed"), TerminalSetting.all(session, agent = bare).map { it.text })
    }

    /**
     * An unknown agent keeps whatever the device reported. Amendment A17 still holds for an agent the
     * app has never met: with no `AgentInfo` at hand there is no list to say the setting does not
     * exist, so the raw ids are shown rather than dropped.
     */
    @Test
    fun unknownAgentKeepsItsIDs() {
        val session = Session(sessionID = "s", deviceID = "d", agent = "aider", title = "Port", cwd = "/tmp",
                              control = SessionControl.terminal, model = "some-model", permissionMode = "yolo",
                              effort = "high", updatedAt = 0)
        assertEquals(listOf("some-model high", "yolo"), TerminalSetting.all(session, agent = null).map { it.text })
    }

    /**
     * A mirrored Grok session shows what its update log knows. A Grok session mirrored from the
     * terminal reports the model and the effort its summary carries, and no permission mode at all —
     * so one chip stands where three would have.
     */
    @Test
    fun grokTerminalChips() {
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.grokSessionID })
        val chips = TerminalSetting.all(session, agent = DemoFixtures.grok)
        assertEquals(listOf("modelCard"), chips.map { it.id })
        assertEquals(listOf("Grok 4.6 High"), chips.map { it.text })
    }

    // What a terminal-held session offers

    /**
     * A terminal-held session names a takeover only where there is one. `docs/DESIGN.md` § "The
     * composer": the way out of a terminal-held session is named only where the agent has one, and
     * only on the status line. The disabled field says the short sentence for every agent.
     */
    @Test
    fun terminalControlNotice() = runTest {
        for ((id, offersTakeover) in listOf("claude" to true, "codex" to false, "grok" to false)) {
            val info = when (id) {
                "claude" -> DemoFixtures.claude
                "codex" -> DemoFixtures.codex
                else -> DemoFixtures.grok
            }
            val session = Session(sessionID = "s", deviceID = "d", agent = id, title = "T", cwd = "/tmp",
                                  state = SessionState.readonly, control = SessionControl.terminal, updatedAt = 0)
            val chat = ChatStore(session = session, channel = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay),
                                 tasks = backgroundScope)
            chat.agent = info
            chat.draft = "hello"

            assertTrue(chat.isReadOnly, id)
            assertEquals(offersTakeover, chat.canTakeover, id)
            val expected = if (offersTakeover) "Controlled by the terminal · take over to send" else "Controlled by the terminal"
            assertEquals(expected, chat.terminalControlNotice, id)
            assertEquals(expected, chat.statusLine, id)
            assertEquals("Controlled by the terminal", chat.sendBlockReason, id)
            assertFalse(chat.canSend, id)
        }
    }

    /** The demo's mirrored Grok session invites no tap that would be refused. The demo's own Grok session, rather than one built for the occasion. */
    @Test
    fun grokSessionNeverPromisesATakeover() = runTest {
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.grokSessionID })
        val chat = ChatStore(session = session, channel = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay),
                             tasks = backgroundScope)
        chat.agent = DemoFixtures.grok
        assertFalse(DemoFixtures.grok.supports(AgentCapability.takeover))
        assertEquals("Controlled by the terminal", chat.sendBlockReason)
        assertEquals("Controlled by the terminal", chat.statusLine)
    }

    // Lists

    /** The agent filter offers every agent the list runs, in label order. */
    @Test
    fun filterOptions() {
        assertEquals(listOf("claude", "codex", "grok", "pi"), SessionListLayout.agents(DemoFixtures.sessions))
    }

    /** And a session of a new agent is found by its name as well as its id. */
    @Test
    fun searchByName() {
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.grokSessionID })
        assertTrue(SessionListLayout.matches(session, query = "grok"))
        assertTrue(SessionListLayout.matches(session, query = "grok build"))
    }
}
