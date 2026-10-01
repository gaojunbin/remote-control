package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Amendment A17: a session a terminal holds shows what the device read from the agent's own
 * transcript, where the live controls would be. Nothing the app can send would change them, so the
 * store hands the composer text rather than an action. Amendment A21: model, effort and speed are
 * one control, so they are one chip, and the tier rides on it.
 */
class TerminalSettingsTests {
    private fun TestScope.store(control: SessionControl, agent: AgentInfo? = DemoFixtures.claude,
                                model: String? = "claude-sonnet-4-5", permissionMode: String? = "auto",
                                effort: String? = "high", speed: String? = null): ChatStore {
        val session = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp",
                              state = SessionState.idle, control = control, model = model,
                              permissionMode = permissionMode, effort = effort, speed = speed)
        val chat = ChatStore(session = session, channel = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay),
                             tasks = backgroundScope)
        chat.agent = agent
        return chat
    }

    // Which sessions show rather than offer

    /** A terminal session shows all of what it chose, and gives up none of it. */
    @Test
    fun terminalHeldSessionsAreTuned() = runTest {
        val terminal = store(control = SessionControl.terminal)
        assertFalse(terminal.allowsModelCardChanges)
        assertTrue(SharedSetting.allCases.all { !terminal.allowsSettingsChanges(it) })
        // An attachment that names no subset keeps every live picker.
        val codex = store(control = SessionControl.shared, agent = DemoFixtures.codex)
        assertTrue(codex.allowsModelCardChanges)
        assertTrue(SharedSetting.allCases.all { codex.allowsSettingsChanges(it) })
        for (control in listOf(SessionControl.remote, SessionControl.none)) {
            val ours = store(control = control)
            assertTrue(ours.allowsModelCardChanges, "$control")
            assertTrue(SharedSetting.allCases.all { ours.allowsSettingsChanges(it) }, "$control")
        }
    }

    /**
     * A shared Claude session offers what the device can type and shows the rest. Amendment A40: the
     * rule is per setting now. A shared Claude session is typed into for the model and the effort, so
     * its card is a control while the permission mode is still a value.
     */
    @Test
    fun sharedClaudeSplitsTheTwo() = runTest {
        val chat = store(control = SessionControl.shared)
        assertTrue(chat.allowsSettingsChanges(SharedSetting.model))
        assertTrue(chat.allowsSettingsChanges(SharedSetting.effort))
        assertFalse(chat.allowsSettingsChanges(SharedSetting.permissionMode))
        assertFalse(chat.allowsSettingsChanges(SharedSetting.speed))
        assertTrue(chat.allowsModelCardChanges)
        assertEquals(listOf("permissionMode"), chat.terminalSettings.map { it.id })
        assertNull(chat.terminalSetting(TerminalSetting.Field.modelCard))
        assertEquals("auto", chat.terminalSetting(TerminalSetting.Field.permissionMode)?.text)

        // The same agent on a machine with no shim types nothing at all.
        val bare = store(control = SessionControl.shared, agent = DemoFixtures.claudeWithoutShim)
        assertFalse(bare.allowsModelCardChanges)
        assertEquals(listOf("modelCard", "permissionMode"), bare.terminalSettings.map { it.id })
    }

    /**
     * The model card needs every setting it carries, not just the model. The card is one control, so
     * it is live only where every setting it carries is. An agent that shared the model but not the
     * effort would draw a card that half works, which is what this forbids.
     */
    @Test
    fun theCardIsAllOrNothing() = runTest {
        val half = AgentInfo(agent = "claude", available = true, models = DemoFixtures.claude.models,
                             efforts = DemoFixtures.claude.efforts, attach = AgentAttach.channel, attachReady = true,
                             sharedSettings = true, sharedSettingsKeys = listOf("model"))
        val chat = store(control = SessionControl.shared, agent = half)
        assertTrue(chat.allowsSettingsChanges(SharedSetting.model))
        assertFalse(chat.allowsSettingsChanges(SharedSetting.effort))
        assertFalse(chat.allowsModelCardChanges)
        assertEquals("Sonnet 4.5 High", chat.terminalSetting(TerminalSetting.Field.modelCard)?.text)
    }

    /** A session is either offered a setting or shown it, never both. */
    @Test
    fun chipsAndPickersAreExclusive() = runTest {
        for (control in listOf(SessionControl.terminal, SessionControl.shared, SessionControl.remote, SessionControl.none)) {
            for (agent in listOf(DemoFixtures.claude, DemoFixtures.codex, null)) {
                val chat = store(control = control, agent = agent)
                val label = "$control, ${agent?.agent}"
                assertEquals(!chat.allowsModelCardChanges, chat.terminalSetting(TerminalSetting.Field.modelCard) != null, label)
                assertEquals(!chat.allowsSettingsChanges(SharedSetting.permissionMode),
                             chat.terminalSetting(TerminalSetting.Field.permissionMode) != null, label)
            }
        }
    }

    /** An unknown agent is still a terminal the app cannot retune. */
    @Test
    fun unknownAgentOnAnAttachedSession() = runTest {
        val chat = store(control = SessionControl.shared, agent = null)
        assertFalse(chat.allowsModelCardChanges)
        assertFalse(chat.allowsSettingsChanges(SharedSetting.permissionMode))
        assertEquals(listOf("claude-sonnet-4-5 high", "auto"), chat.terminalSettings.map { it.text })
    }

    // What each chip says

    /** Each value is labelled by the agent's list, or shown by its raw id. */
    @Test
    fun labelsComeFromTheAgent() = runTest {
        val chips = store(control = SessionControl.terminal).terminalSettings
        assertEquals(listOf("modelCard", "permissionMode"), chips.map { it.id })
        assertEquals(listOf("Sonnet 4.5 High", "auto"), chips.map { it.text })
        assertEquals(listOf("Model", "Permissions"), chips.map { it.field.label })
    }

    /** A value the device has not seen draws nothing at all. */
    @Test
    fun nilValuesAreLeftOut() = runTest {
        assertEquals(listOf("Sonnet 4.5", "auto"), store(control = SessionControl.terminal, effort = null).terminalSettings.map { it.text })
        assertEquals(listOf("High"),
                     store(control = SessionControl.terminal, model = null, permissionMode = null).terminalSettings.map { it.text })
        assertTrue(store(control = SessionControl.terminal, model = null, permissionMode = null, effort = null).terminalSettings.isEmpty())
    }

    /** Effort is shown whether or not the agent advertises the capability. */
    @Test
    fun effortNeedsNoCapability() = runTest {
        // The agent lists its levels but cannot be retuned from this app, so the capability is
        // absent. The chip still says which level it is on.
        val bare = AgentInfo(agent = "claude", available = true, models = DemoFixtures.claude.models,
                             permissionModes = DemoFixtures.claude.permissionModes, efforts = DemoFixtures.claude.efforts)
        assertFalse(bare.supports(AgentCapability.effort))
        assertEquals(listOf("Sonnet 4.5 High", "auto"), store(control = SessionControl.terminal, agent = bare).terminalSettings.map { it.text })
    }

    /**
     * An agent that lists neither drops both, whatever the session carries. Amendment A25: an empty
     * list is the agent saying it has no such setting, so nothing stands where the control would have
     * been — not even the id the device reported.
     */
    @Test
    fun emptyListsDrawNothing() = runTest {
        val bare = AgentInfo(agent = "claude", available = true, models = DemoFixtures.claude.models)
        assertEquals(listOf("Sonnet 4.5"), store(control = SessionControl.terminal, agent = bare).terminalSettings.map { it.text })
    }

    // Following the terminal

    /** A meta with a model moves the chip without a reload. */
    @Test
    fun metaUpdatesTheChip() = runTest {
        val chat = store(control = SessionControl.terminal)
        val frame = AppFrame(json = jsonObjectOf(
            "type" to "session.event", "session_id" to "s",
            "event" to mapOf("seq" to 7, "ts" to 7, "kind" to SessionEvent.metaKind, "model" to "claude-opus-4-1"),
        ))
        chat.receive(frame)
        assertEquals("claude-opus-4-1", chat.session.model)
        assertEquals(listOf("Opus 4.1 High", "auto"), chat.terminalSettings.map { it.text })
        // A meta that says nothing about the other two leaves them alone.
        assertEquals("auto", chat.session.permissionMode)
        assertEquals("high", chat.session.effort)
    }

    // The demo

    /** The demo carries a terminal session and an attached one with all three. */
    @Test
    fun demoFixtures() {
        val sessions = DemoFixtures.sessions
        val terminal = assertNotNull(sessions.firstOrNull { it.sessionID == DemoFixtures.terminalSessionID },
                                     "the demo is missing an A17 session")
        val shared = assertNotNull(sessions.firstOrNull { it.sessionID == DemoFixtures.sharedSessionID },
                                   "the demo is missing an A17 session")
        assertEquals(listOf("Sonnet 4.5 High", "Ask before edits"), TerminalSetting.all(terminal, agent = DemoFixtures.claude).map { it.text })
        // `auto` is a permission mode the transcript has and the agent does not advertise, so the
        // demo carries the raw-id case on screen.
        assertEquals(listOf("Sonnet 4.5 High", "auto"), TerminalSetting.all(shared, agent = DemoFixtures.claude).map { it.text })
    }

    /** The demo's terminal switches model while the session is open. */
    @Test
    fun demoRetunesTheSharedSession() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val session = Session(sessionID = DemoFixtures.sharedSessionID, deviceID = DemoFixtures.macDeviceID, agent = "claude",
                              title = "Tidy the release notes", cwd = "/tmp", state = SessionState.idle,
                              origin = EventSource.terminal, control = SessionControl.shared, model = "claude-sonnet-4-5",
                              permissionMode = "auto", effort = "high")
        val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
        chat.agent = DemoFixtures.claude
        backgroundScope.launch {
            gateway.events.collect { event -> if (event is GatewayEvent.Frame) chat.receive(event.frame) }
        }

        chat.open()
        settle(timeout = 10.seconds, step = 20.milliseconds) { chat.session.model != "claude-sonnet-4-5" }
        // Amendment A40: the card here is a live control, and it follows the terminal's own `/model`
        // in place; only the mode is still a chip.
        assertEquals("Opus 4.1 High", TerminalSetting.modelCardText(chat.session, agent = chat.agent))
        assertEquals(listOf("auto"), chat.terminalSettings.map { it.text })
    }

    // Amendment A21, the tier on the same chip

    /** A terminal-held Codex thread reports its speed tier like anything else. */
    @Test
    fun speedRidesOnTheModelChip() {
        val fast = Session(sessionID = "s", deviceID = "d", agent = "codex", title = "T", cwd = "/tmp",
                           state = SessionState.idle, control = SessionControl.terminal, model = "gpt-5.4-codex",
                           permissionMode = "on-request", effort = "high", speed = "priority")
        val chips = TerminalSetting.all(fast, agent = DemoFixtures.codex)
        assertEquals(listOf("GPT-5.4 Codex High", "Ask when needed"), chips.map { it.text })
        assertEquals("Fast", chips.firstOrNull()?.speed)
        assertEquals("GPT-5.4 Codex, effort High, Fast", chips.firstOrNull()?.spokenValue)
        assertNull(chips.lastOrNull()?.speed)

        // The standard speed adds nothing at all, not even a word.
        val standard = fast.copy(speed = null)
        assertNull(TerminalSetting.all(standard, agent = DemoFixtures.codex).firstOrNull()?.speed)
    }
}
