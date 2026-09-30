package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.FixtureSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amendment A40, typing into a Claude terminal: what `shared_settings_keys` says on the wire.
 * RCCore's suite of this name also drives the demo gateway and `ChatStore` through a typed change;
 * those cases are `core-state`'s and `core-demo`'s to add here.
 */
class TypedTerminalTests {
    private fun agentFixture(name: String): AgentInfo = FixtureSource.json("objects/$name").decode()

    /** The worked example says Claude shares the model and the effort, and no more. */
    @Test
    fun fixtureDecodes() {
        val agent = agentFixture("agent.claude-attach.json")
        assertEquals(AgentAttach.channel, agent.attach)
        assertTrue(agent.attachReady)
        assertTrue(agent.sharedSettings)
        assertEquals(listOf("model", "effort"), agent.sharedSettingsKeys)
        assertTrue(agent.shares(SharedSetting.model) && agent.shares(SharedSetting.effort))
        assertTrue(!agent.shares(SharedSetting.permissionMode) && !agent.shares(SharedSetting.speed))
        // Stop rides the same pseudo-terminal (A42); bytes still cannot reach a live CLI.
        assertTrue(agent.sharedInterrupt && !agent.sharedAttachments)
        // The same typing runs one command, so the capability is there (A27).
        assertTrue(agent.supports(AgentCapability.commands))
    }

    /** The keys survive a re-encode, and are absent when nothing named them. */
    @Test
    fun keysRoundTrip() {
        val agent = agentFixture("agent.claude-attach.json")
        val encoded = JSONValue.encode(agent)
        assertEquals(listOf("model", "effort"), encoded["shared_settings_keys"]?.arrayValue?.mapNotNull { it.stringValue })
        assertEquals(listOf("model", "effort"), encoded.decode<AgentInfo>().sharedSettingsKeys)

        val bare = AgentInfo(agent = "codex", available = true, sharedSettings = true)
        assertNull(JSONValue.encode(bare)["shared_settings_keys"],
                   "an attachment that carries all four says nothing about the subset")
    }

    /** An attachment that names no keys carries all four; one that carries none shares none. */
    @Test
    fun absentKeysMeanEverything() {
        val all = jsonObjectOf("agent" to "codex", "available" to true, "shared_settings" to true).decode<AgentInfo>()
        assertNull(all.sharedSettingsKeys)
        assertTrue(SharedSetting.allCases.all(all::shares))

        val none = jsonObjectOf("agent" to "claude", "available" to true).decode<AgentInfo>()
        assertFalse(SharedSetting.allCases.any(none::shares))
    }

    /**
     * A key this build never heard of is simply not one of ours. The keys are opaque words, not a
     * closed set this build owns: a device that names one nobody here knows changes nothing about
     * the four.
     */
    @Test
    fun unknownKeysAreIgnored() {
        val agent = jsonObjectOf("agent" to "claude", "available" to true, "shared_settings" to true,
                                 "shared_settings_keys" to listOf("model", "sandbox")).decode<AgentInfo>()
        assertEquals(listOf("model", "sandbox"), agent.sharedSettingsKeys)
        assertTrue(agent.shares(SharedSetting.model))
        assertTrue(!agent.shares(SharedSetting.effort) && !agent.shares(SharedSetting.permissionMode))
    }
}
