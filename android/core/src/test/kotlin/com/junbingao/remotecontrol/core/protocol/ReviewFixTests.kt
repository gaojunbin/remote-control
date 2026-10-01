package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Review fixes in these layers. RCCore's suite of this name also holds the timeline's gap, cursor
 * and child-index fixes (`Timeline`) and the composer's send reasons (`ChatStore`); those cases are
 * in `state/ReviewFixTests.kt`.
 */
class ReviewFixTests {
    /** A message longer than the protocol limit is refused before it is sent. */
    @Test
    fun textLimit() {
        assertFailsWith<AttachmentError> {
            GatewayRequest.send(sessionID = "s", text = "a".repeat(RequestLimits.maxTextBytes + 1))
        }
        GatewayRequest.send(sessionID = "s", text = "a".repeat(RequestLimits.maxTextBytes))
    }

    /** Two attachments with the same name stay distinguishable. */
    @Test
    fun attachmentIdentity() {
        val first = OutboundAttachment(name = "notes.png", mime = "image/png", data = byteArrayOf(1))
        val second = OutboundAttachment(name = "notes.png", mime = "image/png", data = byteArrayOf(2))
        assertNotEquals(first.id, second.id)
        val attachments = mutableListOf(first, second)
        attachments.removeAll { it.id == first.id }
        assertEquals(1, attachments.size)
        assertTrue(attachments.first().data.contentEquals(byteArrayOf(2)))
    }

    /** Cache file names cannot collide across similar origins. */
    @Test
    fun slugCollisions() {
        val pairs = listOf(
            "https://a.com" to "https://a-com",
            "https://rc.example.com" to "https://rc.example.org",
            "https://very-long-host.example.com/".repeat(3) + "a" to "https://very-long-host.example.com/".repeat(3) + "b",
        )
        for ((first, second) in pairs) assertNotEquals(LocalCache.slug(first), LocalCache.slug(second), first)
    }

    /** An endpoint placeholder exists so nothing force-unwraps one. */
    @Test
    fun endpointPlaceholder() {
        assertTrue(GatewayEndpoint.placeholder.isSecure)
        assertTrue(GatewayEndpoint.placeholder.origin.isNotEmpty())
    }
}
