package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.decode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amendments A25 and A26 on the demo device: the four agents it advertises and a session of each.
 * The wire cases of RCCore's suite of this name are `protocol.AgentsTests`; its chips, composer and
 * list cases drive `TerminalSetting`, `ChatStore` and `SessionListLayout` and are `core-state`'s.
 */
class AgentsTests {
    private fun agentFixture(name: String): AgentInfo = FixtureSource.json("objects/$name").decode()

    /** The demo device advertises all four, exactly as the fixtures do. */
    @Test
    fun demoDeviceCarriesFour() {
        val mac = assertNotNull(DemoFixtures.devices.firstOrNull { it.deviceID == DemoFixtures.macDeviceID })
        assertEquals(listOf("claude", "codex", "grok", "pi"), mac.availableAgents.map { it.agent })
        for (name in listOf("agent.grok.json", "agent.pi.json")) {
            val fixture = agentFixture(name)
            val demo = assertNotNull(mac.agent(fixture.agent))
            assertEquals(fixture.models, demo.models)
            assertEquals(fixture.defaultModel, demo.defaultModel)
            assertEquals(fixture.permissionModes, demo.permissionModes)
            assertEquals(fixture.defaultPermissionMode, demo.defaultPermissionMode)
            assertEquals(fixture.efforts, demo.efforts)
            assertEquals(fixture.defaultEffort, demo.defaultEffort)
            // Amendment A27 put `commands` on both of these, so the demo device carries it too or
            // the panel would never open on either agent.
            assertEquals(fixture.capabilities, demo.capabilities)
            assertTrue(demo.supports(AgentCapability.commands))
            assertEquals(fixture.attach, demo.attach)
            assertEquals(fixture.attachReady, demo.attachReady)
            // Amendment A28: what an attachment relays is half the contract, and the demo has to
            // report the same three answers or the composer would offer a control the real device
            // refuses.
            assertEquals(fixture.sharedInterrupt, demo.sharedInterrupt)
            assertEquals(fixture.sharedSettings, demo.sharedSettings)
            assertEquals(fixture.sharedAttachments, demo.sharedAttachments)
        }
    }

    /**
     * All four agents take commands, and Claude only behind its shim. Amendment A27 gave three of
     * the four agents slash commands from an app; amendment A40 gave Claude one: the shim's
     * pseudo-terminal is typed into, so `/compact` can be run there — and only where the shim is
     * installed.
     */
    @Test
    fun commandCapability() {
        assertTrue(DemoFixtures.codex.supports(AgentCapability.commands))
        assertTrue(DemoFixtures.grok.supports(AgentCapability.commands))
        assertTrue(DemoFixtures.pi.supports(AgentCapability.commands))
        assertTrue(DemoFixtures.claude.supports(AgentCapability.commands))
        assertFalse(DemoFixtures.claudeWithoutShim.supports(AgentCapability.commands))
        // The capability belongs to the agent, not to its attachment: a device whose daemon is not
        // running still takes commands on a session it runs.
        assertTrue(DemoFixtures.codexWithoutDaemon.supports(AgentCapability.commands))
    }

    /** And carries one demo session of each new agent. */
    @Test
    fun demoSessions() {
        val sessions = DemoFixtures.sessions
        val grok = assertNotNull(sessions.firstOrNull { it.sessionID == DemoFixtures.grokSessionID })
        assertEquals("grok", grok.agent)
        assertEquals(SessionControl.terminal, grok.control)
        assertEquals(EventSource.terminal, grok.origin)
        assertNull(grok.permissionMode)

        val pi = assertNotNull(sessions.firstOrNull { it.sessionID == DemoFixtures.piSessionID })
        assertEquals("pi", pi.agent)
        assertEquals("on-request", pi.permissionMode)
    }

    /**
     * A Grok terminal is watched only where the leader is off, and shared where it is on. Amendment
     * A28: the terminal-held Grok session lives on the machine that leaves `[cli] use_leader` off,
     * because that is the only way a Grok session is still terminal-held — everywhere else it is
     * shared.
     */
    @Test
    fun grokSessionsFollowTheLeader() {
        val sessions = DemoFixtures.sessions
        val devices = DemoFixtures.devices
        val watched = assertNotNull(sessions.firstOrNull { it.sessionID == DemoFixtures.grokSessionID })
        val laptop = assertNotNull(devices.firstOrNull { it.deviceID == watched.deviceID })
        assertEquals(AgentAttach.leader, laptop.agent("grok")?.attach)
        assertEquals(false, laptop.agent("grok")?.attachReady)

        val shared = assertNotNull(sessions.firstOrNull { it.sessionID == DemoFixtures.grokSharedSessionID })
        assertEquals("grok", shared.agent)
        assertEquals(SessionControl.shared, shared.control)
        assertEquals(EventSource.terminal, shared.origin)
        assertEquals(SessionState.running, shared.state)
        val mac = assertNotNull(devices.firstOrNull { it.deviceID == shared.deviceID })
        assertEquals(true, mac.agent("grok")?.attachReady)
    }

    /** And no session of an agent the protocol withdrew. */
    @Test
    fun noCursorSessionSurvives() {
        assertFalse(DemoFixtures.sessions.any { it.agent == "cursor" })
        assertFalse(DemoFixtures.devices.any { device -> device.agents.any { it.agent == "cursor" } })
    }
}
