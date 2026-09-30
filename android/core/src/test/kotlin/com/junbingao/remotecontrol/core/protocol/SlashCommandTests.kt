package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.FixtureSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slash commands (A27): the object on the wire and the two requests. RCCore's suite of this name
 * also holds what a draft means (`SlashDraft`), what the panel shows (`CommandSection`) and the
 * store's half; those cases are `core-state`'s to add here.
 */
class SlashCommandTests {
    /** A command decodes from the protocol's own worked list. */
    @Test
    fun replyDecodes() {
        val reply = FixtureSource.json("app/reply.session.commands.json")
        val result = assertNotNull(reply["result"]).decode<CommandsResult>()
        assertEquals(listOf("compact", "review", "init", "status", "release-notes", "skill:pdf-tables"),
                     result.commands.map { it.name })
        val review = assertNotNull(result.commands.firstOrNull { it.name == "review" })
        assertEquals("instructions", review.argument)
        assertTrue(review.takesArgument)
        assertEquals("Built-in", review.group)
        assertEquals("/review", review.slash)
        assertEquals("/review the retry logic", review.line(argument = "the retry logic"))
        assertEquals("/review", review.line(argument = null))

        val compact = assertNotNull(result.commands.firstOrNull())
        assertFalse(compact.takesArgument, "a command that takes nothing shows no placeholder")
    }

    /** An empty placeholder or group is a device saying nothing. */
    @Test
    fun emptyStringsDecodeAsAbsent() {
        val command = jsonObjectOf("name" to "compact", "description" to "Summarise", "argument" to "", "group" to "")
            .decode<Command>()
        assertNull(command.argument)
        assertNull(command.group)
        assertFalse(command.takesArgument)
    }

    /** The two requests carry exactly what the fixtures do. */
    @Test
    fun requestsMatchTheFixtures() {
        val list = FixtureSource.json("app/session.commands.json")
        val listed = GatewayRequest.commands(sessionID = list["session_id"]?.stringValue ?: "")
        assertEquals("session.commands", listed.json["type"]?.stringValue)
        assertEquals(list["session_id"], listed.json["session_id"])

        val run = FixtureSource.json("app/session.command.json")
        val request = GatewayRequest.command(sessionID = run["session_id"]?.stringValue ?: "",
                                             name = run["name"]?.stringValue ?: "",
                                             argument = run["argument"]?.stringValue)
        assertEquals("session.command", request.json["type"]?.stringValue)
        assertEquals(run["name"], request.json["name"])
        assertEquals(run["argument"], request.json["argument"])
        assertNull(GatewayRequest.command(sessionID = "s", name = "compact").json["argument"],
                   "a command that takes nothing sends no argument at all")
    }
}
