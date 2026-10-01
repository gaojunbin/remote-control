package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The decoding case of RCCore's suite of this name is in `protocol/GrokLeaderTests.kt`, and
// `demoSharedSession`, which reads the demo's own Grok session, is in `demo/GrokLeaderTests.kt`.

/**
 * Amendment A28: Grok Build shares a terminal session through its leader, one backend process per
 * machine that the TUI joins and the device joins as another client of. The app learns this the way
 * it learns every attachment: from `attach` and the three booleans beside it, and from `control`.
 */
class GrokLeaderTests {
    private fun TestScope.store(state: SessionState = SessionState.running, agent: AgentInfo?,
                                control: SessionControl = SessionControl.shared): ChatStore {
        val session = Session(sessionID = "s", deviceID = "d", agent = "grok", title = "T", cwd = "/tmp", state = state,
                              origin = EventSource.terminal, control = control)
        val chat = ChatStore(session = session, channel = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay),
                             tasks = backgroundScope)
        chat.agent = agent
        chat.draft = "hello"
        return chat
    }

    // What the composer offers

    /** A session on the leader keeps every control except the attachment. */
    @Test
    fun sharedControls() = runTest {
        val chat = store(agent = DemoFixtures.grok)
        assertTrue(chat.isAttached)
        assertFalse(chat.isReadOnly)
        assertTrue(chat.canSend)
        assertTrue(chat.canStop)
        assertTrue(chat.allowsModelCardChanges)
        assertTrue(chat.allowsSettingsChanges(SharedSetting.permissionMode))
        assertFalse(chat.allowsAttachments)
        assertFalse(chat.canTakeover)
        assertNull(chat.attachHint)
    }

    /**
     * Stop needs the capability as well as the relay. Stop needs both halves: the agent's own
     * capability and an attachment that relays an interrupt. Grok has both, so the turn a TUI set off
     * is stoppable from here.
     */
    @Test
    fun stopNeedsBothHalves() = runTest {
        assertTrue(store(agent = DemoFixtures.grok).canStop)
        val noRelay = AgentInfo(agent = "grok", available = true, capabilities = listOf(AgentCapability.interrupt),
                                attach = AgentAttach.leader, attachReady = true)
        assertFalse(store(agent = noRelay).canStop)
        val noCapability = AgentInfo(agent = "grok", available = true, attach = AgentAttach.leader, attachReady = true,
                                     sharedInterrupt = true)
        assertFalse(store(agent = noCapability).canStop)
    }

    // The hint on a session that is still the terminal's

    /** A machine that is not in the leader says how to put it there. */
    @Test
    fun hintNamesTheSetupCommand() = runTest {
        val chat = store(state = SessionState.readonly, agent = DemoFixtures.grokWithoutLeader, control = SessionControl.terminal)
        assertEquals(ChatStore.AttachHint.enableLeader, chat.attachHint)
        assertEquals("Controlled by the terminal", chat.sendBlockReason)
        assertFalse(chat.canTakeover)
    }

    /** A prepared machine blames this grok instead. */
    @Test
    fun hintBlamesTheProcessWhenReady() = runTest {
        assertEquals(ChatStore.AttachHint.restartSession,
                     store(state = SessionState.readonly, agent = DemoFixtures.grok, control = SessionControl.terminal).attachHint)
    }

    // The demo

    /**
     * A message for a leader session is sent, not held. The device is a real client of the leader,
     * so a prompt runs in the conversation the TUI is in rather than being held for a shim to type.
     */
    @Test
    fun demoSendsThroughTheLeader() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.grokSharedSessionID })
        val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
        chat.agent = DemoFixtures.grok
        chat.draft = "cap it at thirty seconds instead"
        chat.send(mode = SendMode.interrupt)
        assertEquals(SendAcceptance.sent, chat.lastAcceptance)
        assertNull(chat.errorMessage)

        chat.set(effort = "low")
        assertEquals("low", chat.session.effort)
        assertNull(chat.errorMessage)
    }
}
