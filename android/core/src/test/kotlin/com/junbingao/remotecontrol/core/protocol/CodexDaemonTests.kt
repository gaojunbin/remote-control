package com.junbingao.remotecontrol.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amendment A11, the shared Codex daemon: the two booleans on the wire and the `elsewhere`
 * decision. RCCore's suite of this name also drives `ChatStore` and the demo gateway through a
 * shared thread; those cases are `core-state`'s and `core-demo`'s to add here.
 */
class CodexDaemonTests {
    // The two booleans

    /** An agent reports whether a shared session keeps its settings and attachments. */
    @Test
    fun decoding() {
        val json = jsonObjectOf("agent" to "codex", "available" to true, "attach" to "daemon",
                                "attach_ready" to true, "shared_interrupt" to true,
                                "shared_settings" to true, "shared_attachments" to true)
        val agent = json.decode<AgentInfo>()
        assertTrue(agent.sharedSettings)
        assertTrue(agent.sharedAttachments)
        assertEquals(true, JSONValue.encode(agent)["shared_settings"]?.boolValue)
        assertEquals(true, JSONValue.encode(agent)["shared_attachments"]?.boolValue)
    }

    /** Both default to false, so an older device loses nothing and gains nothing. */
    @Test
    fun decodingDefaults() {
        val bare = jsonObjectOf("agent" to "codex", "available" to true).decode<AgentInfo>()
        assertFalse(bare.sharedSettings)
        assertFalse(bare.sharedAttachments)
    }

    /** A boolean that is not a boolean is refused rather than coerced. */
    @Test
    fun decodingRejectsAString() {
        val json = jsonObjectOf("agent" to "codex", "available" to true, "shared_settings" to "yes")
        assertTrue(runCatching { json.decode<AgentInfo>() }.isFailure)
    }

    // The `elsewhere` decision

    private fun approval(decision: ApprovalDecision?): ApprovalPayload = ApprovalPayload(
        requestID = "r", tool = "shell", kind = ToolKind.shell, title = "npm run typecheck",
        options = listOf(ApprovalOption(id = "allow", label = "Allow", style = OptionStyle.primary),
                         ApprovalOption(id = "allow_session", label = "Allow for this session",
                                        style = OptionStyle.secondary),
                         ApprovalOption(id = "allow_always", label = "Always allow commands like this",
                                        style = OptionStyle.secondary),
                         ApprovalOption(id = "deny", label = "Deny", style = OptionStyle.danger)),
        status = if (decision == null) RequestStatus.pending else RequestStatus.resolved, decision = decision)

    /** A request answered elsewhere has no option to name. */
    @Test
    fun elsewhereNamesNothing() {
        val resolved = approval(ApprovalDecision(optionID = ApprovalPayload.elsewhereOptionID, by = EventSource.terminal))
        assertNull(resolved.resolvedOptionLabel)
        // And `elsewhere` is never one of the choices the card can offer.
        assertFalse(resolved.options.any { it.id == ApprovalPayload.elsewhereOptionID })
    }

    /** An option the block did offer is named by its label. */
    @Test
    fun knownOptionIsNamed() {
        val resolved = approval(ApprovalDecision(optionID = "allow_session", by = EventSource.remote))
        assertEquals("Allow for this session", resolved.resolvedOptionLabel)
    }

    /** An option id from a newer device renders verbatim rather than blanking the card. */
    @Test
    fun unknownOptionRendersVerbatim() {
        val resolved = approval(ApprovalDecision(optionID = "allow_next_week", by = EventSource.terminal))
        assertEquals("allow_next_week", resolved.resolvedOptionLabel)
    }

    /** A pending request names nothing, because nothing was decided. */
    @Test
    fun pendingNamesNothing() {
        assertNull(approval(null).resolvedOptionLabel)
    }

    /** The four decisions place one primary, one danger and two in between. */
    @Test
    fun fourOptionsKeepTheirPlaces() {
        val pending = approval(null)
        assertEquals("allow", pending.primaryOption?.id)
        assertEquals("deny", pending.dangerOption?.id)
        assertEquals(listOf("allow_session", "allow_always"), pending.otherOptions.map { it.id })
    }

    /** An elsewhere decision decodes off the wire with its source. */
    @Test
    fun elsewhereDecoding() {
        val json = jsonObjectOf("seq" to 17, "ts" to 1, "kind" to "approval", "block_id" to "ap",
                                "request_id" to "r", "tool" to "shell", "tool_kind" to "shell",
                                "title" to "npm run typecheck",
                                "options" to listOf(mapOf("id" to "allow", "label" to "Allow", "style" to "primary")),
                                "status" to "resolved",
                                "decision" to mapOf("option_id" to "elsewhere", "by" to "terminal"))
        val event = json.decode<SessionEvent>()
        assertEquals(ApprovalPayload.elsewhereOptionID, event.approval?.decision?.optionID)
        assertEquals(EventSource.terminal, event.approval?.decision?.by)
        assertNull(event.approval?.resolvedOptionLabel)
    }
}
