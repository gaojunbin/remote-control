package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SharedSetting
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
import kotlin.time.Duration.Companion.seconds

// The decoding and approval cases of RCCore's suite of this name are in
// `protocol/CodexDaemonTests.kt`, and `demoFixtures`, which reads the demo's shared thread, is in
// `demo/CodexDaemonTests.kt`.

/**
 * Amendment A11: Codex shares a terminal thread through the app-server daemon. The attachment
 * carries more than a Claude channel does, and it says so with `shared_settings` and
 * `shared_attachments`. Two requests that A10 kept in the terminal come back to the app when those
 * are true.
 */
class CodexDaemonTests {
    private fun TestScope.store(state: SessionState = SessionState.running, agent: AgentInfo?,
                                control: SessionControl = SessionControl.shared): ChatStore {
        val session = Session(sessionID = "s", deviceID = "d", agent = "codex", title = "T", cwd = "/tmp", state = state,
                              origin = EventSource.terminal, control = control)
        val chat = ChatStore(session = session, channel = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay),
                             tasks = backgroundScope)
        chat.agent = agent
        chat.draft = "hello"
        return chat
    }

    private val daemon: AgentInfo
        get() = AgentInfo(agent = "codex", available = true,
                          capabilities = listOf(AgentCapability.interrupt, AgentCapability.queue, AgentCapability.steer,
                                                AgentCapability.attachments, AgentCapability.effort,
                                                AgentCapability.history),
                          attach = AgentAttach.daemon, attachReady = true, sharedInterrupt = true, sharedSettings = true,
                          sharedAttachments = true)

    // The two booleans

    /** shared_settings decides whether the pickers open on a shared session. */
    @Test
    fun settingsFollowTheBoolean() = runTest {
        assertTrue(store(agent = daemon).allowsSettingsChanges(SharedSetting.permissionMode))
        assertFalse(store(agent = DemoFixtures.claude).allowsSettingsChanges(SharedSetting.permissionMode))
        // An agent the device did not describe grants nothing.
        assertFalse(store(agent = null).allowsSettingsChanges(SharedSetting.permissionMode))
        assertFalse(store(agent = null).allowsModelCardChanges)
        // And a session no CLI owns is unaffected either way.
        assertTrue(store(agent = DemoFixtures.claude, control = SessionControl.remote)
                       .allowsSettingsChanges(SharedSetting.permissionMode))
    }

    /** shared_attachments decides whether the attachment button opens. */
    @Test
    fun attachmentsFollowTheBoolean() = runTest {
        assertTrue(store(agent = daemon).allowsAttachments)
        assertFalse(store(agent = DemoFixtures.claude).allowsAttachments)
        assertFalse(store(agent = null).allowsAttachments)
        assertTrue(store(agent = DemoFixtures.claude, control = SessionControl.remote).allowsAttachments)
        // A terminal session still takes no input at all, whatever it reports.
        assertFalse(store(state = SessionState.readonly, agent = daemon, control = SessionControl.terminal).allowsAttachments)
    }

    /** Stop stays on the interrupt capability and shared_interrupt, not the new pair. */
    @Test
    fun stopIsUnchanged() = runTest {
        assertTrue(store(agent = daemon).canStop)
        val noInterrupt = AgentInfo(agent = "codex", available = true, capabilities = listOf(AgentCapability.interrupt),
                                    attach = AgentAttach.daemon, attachReady = true, sharedSettings = true,
                                    sharedAttachments = true)
        assertFalse(store(agent = noInterrupt).canStop)
    }

    /** A running turn is steered by an agent that lists steer, queued otherwise. */
    @Test
    fun steerWording() = runTest {
        val steering = store(agent = daemon)
        assertTrue(steering.steersRunningTurn)
        assertEquals("Working · your message will steer the turn", steering.statusLine)

        val remote = store(state = SessionState.running, agent = daemon, control = SessionControl.remote)
        assertEquals("Working · your message will steer the turn", remote.statusLine)

        // Claude does not list `steer`, so its wording is unchanged.
        val queueing = store(state = SessionState.running, agent = DemoFixtures.claude, control = SessionControl.remote)
        assertFalse(queueing.steersRunningTurn)
        assertEquals("Working · your message will be queued", queueing.statusLine)

        // A message already waiting still says how many are waiting.
        assertFalse(store(state = SessionState.idle, agent = daemon, control = SessionControl.remote).steersRunningTurn)
    }

    // Protocol send modes on a shared thread

    private fun TestScope.sharedCodexChat(gateway: DemoGateway): ChatStore {
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.codexSharedSessionID },
                                    "the demo is missing the shared Codex thread")
        val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
        chat.agent = DemoFixtures.codex
        return chat
    }

    /** auto on a running shared thread steers it, and says steered. */
    @Test
    fun autoSteersTheSharedThread() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = sharedCodexChat(gateway)
        chat.draft = "also check the drawer's tests"
        chat.send(mode = SendMode.auto)
        assertEquals(SendAcceptance.steered, chat.lastAcceptance)
        assertNull(chat.errorMessage)

        // Amendment A14: the acceptance says the device has the message, not that the agent has
        // read it, so the row stays at the foot until the device's own block arrives.
        assertEquals(1, chat.timeline.optimistic.size)
        assertEquals("also check the drawer's tests", chat.timeline.roots.lastOrNull()?.pending?.text)
        assertEquals(true, chat.timeline.roots.lastOrNull()?.pending?.isSteering)
        assertTrue(chat.pendingSends.isEmpty(), "and nothing asks to send it a second time")
    }

    /** queue on the same thread holds the message instead. */
    @Test
    fun queueHoldsTheSharedThread() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = sharedCodexChat(gateway)
        chat.draft = "and then run the linter"
        chat.send(mode = SendMode.queue)
        assertEquals(SendAcceptance.queued, chat.lastAcceptance)
    }

    /** An option the block never offered is refused rather than relayed. */
    @Test
    fun approveRefusesAnUnofferedOption() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = sharedCodexChat(gateway)
        chat.approve(requestID = "demo-approval-codex", optionID = ApprovalPayload.elsewhereOptionID)
        assertTrue(chat.errorMessage != null)
        chat.clearError()
        chat.approve(requestID = "demo-approval-codex", optionID = "allow_session")
        assertNull(chat.errorMessage)
    }

    // The demo

    /** The daemon-less Codex still explains how to make the next run controllable. */
    @Test
    fun withoutTheDaemon() = runTest {
        val agent = DemoFixtures.codexWithoutDaemon
        assertEquals(AgentAttach.daemon, agent.attach)
        assertFalse(agent.attachReady)
        assertFalse(agent.sharedSettings)
        assertFalse(agent.sharedAttachments)
        val chat = store(state = SessionState.readonly, agent = agent, control = SessionControl.terminal)
        assertEquals(ChatStore.AttachHint.startDaemon, chat.attachHint)
    }

    /** The demo applies session.set to a shared thread whose device relays it. */
    @Test
    fun demoRetunesTheThread() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.codexSharedSessionID },
                                    "the demo is missing the shared Codex thread")
        val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
        chat.agent = DemoFixtures.codex
        chat.set(effort = "high")
        assertEquals("high", chat.session.effort)
        assertNull(chat.errorMessage)

        // Amendment A40: the same request on a Claude channel is typed into the terminal instead, and
        // lands once the transcript confirms it.
        val claudeShared = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.sharedSessionID },
                                         "the demo is missing the attached Claude session")
        val relayed = ChatStore(session = claudeShared, channel = gateway, tasks = backgroundScope)
        relayed.agent = DemoFixtures.claude
        relayed.set(effort = "low")
        assertEquals("low", relayed.session.effort)
        assertNull(relayed.errorMessage)

        // The permission mode is not in `shared_settings_keys`, so it is still refused, and a
        // refused request leaves the value exactly as it was.
        relayed.set(permissionMode = "plan")
        assertEquals(claudeShared.permissionMode, relayed.session.permissionMode)
        assertTrue(relayed.errorMessage != null)
    }

    /** The demo interrupts a shared thread only when the attachment can. */
    @Test
    fun demoStopsTheThread() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.codexSharedSessionID },
                                    "the demo is missing the shared Codex thread")
        val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
        chat.agent = DemoFixtures.codex
        backgroundScope.launch {
            gateway.events.collect { event -> if (event is GatewayEvent.Frame) chat.receive(event.frame) }
        }

        assertTrue(chat.canStop)
        chat.stop()
        assertNull(chat.errorMessage)
        settle(timeout = 10.seconds) { !chat.isRunning }
        assertFalse(chat.isRunning)

        // On a Claude terminal the device types Escape (A42): idle, the demo has nothing to stop and
        // refuses nothing. Without the shim the app offers no Stop at all, because the channel alone
        // cannot interrupt.
        val relayedSession = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.sharedSessionID },
                                           "the demo is missing the attached Claude session")
        val relayed = ChatStore(session = relayedSession, channel = gateway, tasks = backgroundScope)
        relayed.agent = DemoFixtures.claude
        relayed.stop()
        assertNull(relayed.errorMessage)
        relayed.agent = DemoFixtures.claudeWithoutShim
        assertFalse(relayed.canStop)
    }
}
