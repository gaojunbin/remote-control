package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.FixtureSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Amendments A25 and A26: the agents beside Claude and Codex — the names the app draws and what
 * the device advertises. RCCore's suite of this name also holds the demo's agents and sessions,
 * the chips (`TerminalSetting`), the composer (`ChatStore`) and the list (`SessionListLayout`);
 * those cases are `core-state`'s and `core-demo`'s to add here.
 */
class AgentsTests {
    private fun agentFixture(name: String): AgentInfo = FixtureSource.json("objects/$name").decode()

    // Names

    /** Every agent the device knows is named. */
    @Test
    fun names() {
        val table = listOf("claude" to "Claude Code", "codex" to "Codex", "grok" to "Grok Build", "pi" to "pi")
        for ((agent, name) in table) assertEquals(name, AgentLabel.name(agent), agent)
    }

    /** An agent nobody knows renders as itself, marked by its first letter. */
    @Test
    fun unknownAgent() {
        assertEquals("aider", AgentLabel.name("aider"))
        assertEquals("A", AgentLabel.initial("aider"))
        assertEquals("", AgentLabel.initial(""))
    }

    /**
     * Cursor is gone, and reads as any other unknown id would. Amendment A26: Cursor is no longer an
     * agent id a device reports, so it falls through to the rule for an id nobody knows.
     */
    @Test
    fun cursorIsWithdrawn() {
        assertEquals("cursor", AgentLabel.name("cursor"))
        assertEquals("C", AgentLabel.initial("cursor"))
    }

    // What the device advertises

    /** The two new agents decode exactly as the protocol's worked examples. */
    @Test
    fun fixturesDecode() {
        val grok = agentFixture("agent.grok.json")
        assertEquals("grok", grok.agent)
        assertEquals("1.0.30", grok.version)
        assertEquals(listOf("grok-4.6", "grok-4.5"), grok.models.map { it.id })
        assertEquals(listOf("default", "acceptEdits", "auto", "dontAsk", "plan", "bypassPermissions"),
                     grok.permissionModes.map { it.id })
        assertEquals(listOf("low", "medium", "high", "xhigh"), grok.efforts.map { it.id })
        assertTrue(grok.supports(AgentCapability.effort))
        // Amendment A28: Grok Build attaches through the leader its terminals join. The leader
        // relays an interrupt and the session settings to every client of it; a Grok prompt carries
        // no images.
        assertEquals(AgentAttach.leader, grok.attach)
        assertTrue(grok.attachReady)
        assertTrue(grok.sharedInterrupt && grok.sharedSettings)
        assertFalse(grok.sharedAttachments)

        // Amendment A26: pi's three permission modes are the device's own, and the extension that
        // enforces them attaches its terminal sessions too.
        val pi = agentFixture("agent.pi.json")
        assertEquals(listOf("untrusted", "on-request", "never"), pi.permissionModes.map { it.id })
        assertEquals("on-request", pi.defaultPermissionMode)
        assertEquals(listOf("off", "low", "medium", "high"), pi.efforts.map { it.id })
        assertTrue(pi.supports(AgentCapability.steer))
        assertTrue(pi.supports(AgentCapability.attachments))
        assertEquals(AgentAttach.extension, pi.attach)
        assertTrue(pi.attachReady)
        assertTrue(pi.sharedInterrupt && pi.sharedSettings && pi.sharedAttachments)
    }
}
