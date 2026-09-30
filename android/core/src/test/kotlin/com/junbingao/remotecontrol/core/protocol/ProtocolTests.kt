package com.junbingao.remotecontrol.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Wire protocol. */
class ProtocolTests {
    /** An unknown event kind keeps its payload instead of being dropped. */
    @Test
    fun unknownEventKind() {
        val event = JSONValue.parse(
            """{"seq": 7, "ts": 12, "kind": "screenshot", "block_id": "s1", "url": "https://example.invalid/a.png"}"""
                .encodeToByteArray()).decode<SessionEvent>()
        val body = assertIs<SessionEventBody.Unknown>(event.body, "expected an unknown body")
        assertEquals("screenshot", body.kind)
        assertEquals("https://example.invalid/a.png", body.raw["url"]?.stringValue)

        val encoded = JSONValue.encode(event)
        assertEquals("https://example.invalid/a.png", encoded["url"]?.stringValue)
        assertEquals("screenshot", encoded["kind"]?.stringValue)
    }

    /** A field this build does not model survives a round trip. */
    @Test
    fun unknownFieldsSurvive() {
        val source = jsonObjectOf("seq" to 3, "ts" to 4, "kind" to "assistant_text", "block_id" to "a",
                                  "text" to "hello", "done" to true, "confidence" to 0.9)
        val event = source.decode<SessionEvent>()
        val encoded = JSONValue.encode(event)
        assertEquals(0.9, encoded["confidence"]?.doubleValue)
        assertEquals("hello", encoded["text"]?.stringValue)
    }

    /** An unknown enumeration value decodes rather than throwing. */
    @Test
    fun unknownEnumeration() {
        val state = JSONValue.parse("\"hibernating\"".encodeToByteArray()).decode<SessionState>()
        assertEquals("hibernating", state.rawValue)
        assertFalse(state.isWorking)
    }

    /** Amendment A1: the tool category rides in tool_kind. */
    @Test
    fun toolKindField() {
        val event = jsonObjectOf("seq" to 1, "ts" to 1, "kind" to "tool_call", "block_id" to "b",
                                 "tool" to "Bash", "tool_kind" to "shell", "title" to "pytest",
                                 "status" to "running").decode<SessionEvent>()
        assertEquals(SessionEvent.toolCallKind, event.kind)
        assertEquals(ToolKind.shell, event.toolCall?.kind)
        val encoded = JSONValue.encode(event)
        assertEquals("tool_call", encoded["kind"]?.stringValue)
        assertEquals("shell", encoded["tool_kind"]?.stringValue)
    }

    /** Amendment A2: usage decodes without a cost or a context window. */
    @Test
    fun optionalUsageFields() {
        val usage = jsonObjectOf("input_tokens" to 10, "output_tokens" to 5, "total_tokens" to 15).decode<SessionUsage>()
        assertEquals(15, usage.totalTokens)
        assertNull(usage.costUSD)
        assertNull(usage.contextFraction)
    }

    /** Amendment A3: an inbound attachment carries a size, an outbound one carries bytes. */
    @Test
    fun attachmentShapes() {
        val message = jsonObjectOf(
            "text" to "look", "attachments" to listOf(mapOf("name" to "a.png", "mime" to "image/png", "size" to 12)),
        ).decode<UserMessagePayload>()
        assertEquals(12, message.attachments.firstOrNull()?.size)

        val request = GatewayRequest.send(sessionID = "s", text = "look", attachments = listOf(
            OutboundAttachment(name = "a.png", mime = "image/png", data = byteArrayOf(1, 2, 3)),
        ))
        val attachment = request.json["attachments"]?.arrayValue?.firstOrNull()
        assertEquals("AQID", attachment?.get("data_base64")?.stringValue)
        assertNull(attachment?.get("size"))
    }

    /** A protocol mismatch is reported, not guessed around. */
    @Test
    fun protocolGate() {
        val hello = jsonObjectOf("type" to "hello", "protocol" to 2).decode<HelloFrame>()
        assertNotEquals(RemoteProtocol.version, hello.protocolVersion)
    }

    /** Attachment limits are enforced before a message leaves the composer. */
    @Test
    fun attachmentCountLimit() {
        for (count in listOf(9, 12)) {
            val attachment = OutboundAttachment(name = "a", mime = "image/png", data = byteArrayOf(0))
            assertFailsWith<AttachmentError>("$count attachments") {
                GatewayRequest.send(sessionID = "s", text = "x", attachments = List(count) { attachment })
            }
        }
    }

    /** A retry reuses the original request id so the device can dedupe it. */
    @Test
    fun retryKeepsRequestID() {
        val first = GatewayRequest.send(id = "abc", sessionID = "s", text = "hello")
        val retry = GatewayRequest.send(id = "abc", sessionID = "s", text = "hello")
        assertEquals(first.id, retry.id)
        assertEquals("abc", retry.json["id"]?.stringValue)
    }
}
