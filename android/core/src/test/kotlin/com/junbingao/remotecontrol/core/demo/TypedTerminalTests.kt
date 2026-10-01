package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.protocol.decode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Amendment A40 on the demo device: its Claude is the protocol's worked example of a terminal the
 * device types into. The wire cases of RCCore's suite of this name are `protocol.TypedTerminalTests`;
 * the changes and commands it drives through `ChatStore` are `core-state`'s.
 */
class TypedTerminalTests {
    private fun agentFixture(name: String): AgentInfo = FixtureSource.json("objects/$name").decode()

    /** The demo's Claude is the worked example, and its shimless twin shares nothing. */
    @Test
    fun demoMatchesTheFixture() {
        val fixture = agentFixture("agent.claude-attach.json")
        assertEquals(fixture.sharedSettings, DemoFixtures.claude.sharedSettings)
        assertEquals(fixture.sharedSettingsKeys, DemoFixtures.claude.sharedSettingsKeys)
        assertEquals(fixture.sharedInterrupt, DemoFixtures.claude.sharedInterrupt)
        assertEquals(fixture.sharedAttachments, DemoFixtures.claude.sharedAttachments)
        assertEquals(fixture.supports(AgentCapability.commands), DemoFixtures.claude.supports(AgentCapability.commands))

        assertFalse(DemoFixtures.claudeWithoutShim.sharedSettings)
        assertNull(DemoFixtures.claudeWithoutShim.sharedSettingsKeys, "the field is never present without the boolean")
        assertFalse(SharedSetting.allCases.any { DemoFixtures.claudeWithoutShim.shares(it) })
    }
}
