package com.junbingao.remotecontrol.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amendment A10, shared control: decoding. RCCore's suite of this name also drives the composer
 * (`ChatStore`) and the demo's attached sessions; those cases are in `state/SharedControlTests.kt`
 * and `demo/SharedControlTests.kt`.
 */
class SharedControlTests {
    /** control accepts shared and keeps an unknown value distinct. */
    @Test
    fun controlDecoding() {
        val session = jsonObjectOf("session_id" to "s", "device_id" to "d", "control" to "shared").decode<Session>()
        assertEquals(SessionControl.shared, session.control)
        assertTrue(session.isAttached)
        assertFalse(session.isControlledByTerminal)

        val other = jsonObjectOf("session_id" to "s", "device_id" to "d", "control" to "attached").decode<Session>()
        assertEquals("attached", other.control.rawValue)
        assertFalse(other.isAttached)
        assertFalse(other.isControlledByTerminal)
    }

    /** An agent reports how and whether it can be attached. */
    @Test
    fun agentAttachDecoding() {
        val agent = jsonObjectOf("agent" to "claude", "available" to true, "attach" to "channel",
                                 "attach_ready" to true, "shared_interrupt" to false).decode<AgentInfo>()
        assertEquals(AgentAttach.channel, agent.attach)
        assertTrue(agent.attachReady)
        assertFalse(agent.sharedInterrupt)

        val bare = jsonObjectOf("agent" to "codex", "available" to true).decode<AgentInfo>()
        assertNull(bare.attach)
        assertFalse(bare.attachReady)
        assertFalse(bare.sharedInterrupt)
    }

    /** delivery decodes, defaults to absent, and survives a re-encode. */
    @Test
    fun deliveryDecoding() {
        for (value in listOf("delivered", "absorbed")) {
            val event = jsonObjectOf("seq" to 1, "ts" to 1, "kind" to "user_message", "block_id" to "u1",
                                     "text" to "later", "source" to "remote", "delivery" to value).decode<SessionEvent>()
            assertEquals(value, event.userMessage?.delivery?.rawValue)
            assertEquals(value, JSONValue.encode(event)["delivery"]?.stringValue)
        }
        val plain = jsonObjectOf("seq" to 1, "ts" to 1, "kind" to "user_message", "text" to "now")
        assertNull(plain.decode<SessionEvent>().userMessage?.delivery)
    }
}
