package com.junbingao.remotecontrol.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Amendment A28, the Grok leader: the wire. RCCore's suite of this name also drives `ChatStore` and
 * the demo's Grok session; those cases are in `state/GrokLeaderTests.kt` and
 * `demo/GrokLeaderTests.kt`.
 */
class GrokLeaderTests {
    /** The leader is an attach mode of its own, and an unknown one still decodes. */
    @Test
    fun decoding() {
        val json = jsonObjectOf("agent" to "grok", "available" to true, "attach" to "leader",
                                "attach_ready" to true, "shared_interrupt" to true,
                                "shared_settings" to true, "shared_attachments" to false)
        val agent = json.decode<AgentInfo>()
        assertEquals(AgentAttach.leader, agent.attach)
        assertTrue(agent.attachReady)
        assertTrue(agent.sharedInterrupt && agent.sharedSettings)
        assertFalse(agent.sharedAttachments)
        assertEquals("leader", JSONValue.encode(agent)["attach"]?.stringValue)

        // A mode this build has never heard of keeps its name rather than becoming null, which is
        // what lets a hint be worded for it later.
        val later = jsonObjectOf("agent" to "grok", "available" to true, "attach" to "swarm")
        assertEquals(AgentAttach(rawValue = "swarm"), later.decode<AgentInfo>().attach)
    }
}
