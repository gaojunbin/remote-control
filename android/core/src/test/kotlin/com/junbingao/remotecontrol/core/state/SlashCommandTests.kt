package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The wire cases of RCCore's suite of this name are in `protocol/SlashCommandTests.kt`. The cases that
// read the demo's command lists or drive a session through the demo gateway — `filtering`,
// `matching`, `sectioning`, `piSessionOffersItsCommands`, `claudeOffersOneCommand`,
// `claudeWithoutShimOffersNothing`, `terminalSessionDrawsNoPanel`, `theRowIsDrawnAtOnce`,
// `runningACommand`, `aCommandWaitsForTheTurn`, `informationReadsAsAToolCall`, `unknownNameIsRefused`,
// `refreshRule` — arrive with the demo gateway.

/**
 * Amendment A27: the terminal's `/` menu, on the phone. What is checked here is the rule the panel,
 * the hint line and Send all read — whether a draft is a command at all, which rows it leaves on
 * screen, and what the store does with it — plus the frames that carry it.
 */
class SlashCommandTests {
    // What a draft means

    /** The slash has to be the first character, or the draft is prose. */
    @Test
    fun onlyALeadingSlash() {
        assertNull(SlashDraft.parse("look in /etc/hosts"))
        assertNull(SlashDraft.parse(" /compact"))
        assertNull(SlashDraft.parse(""))
        assertNotNull(SlashDraft.parse("/"))
    }

    /** A bare slash opens the whole list; letters after it are the filter. */
    @Test
    fun namePartsOfADraft() {
        val bare = assertNotNull(SlashDraft.parse("/"))
        assertTrue(bare.name.isEmpty())
        assertFalse(bare.isComplete)
        assertNull(bare.argument)

        val typing = assertNotNull(SlashDraft.parse("/rev"))
        assertEquals("rev", typing.name)
        assertFalse(typing.isComplete)
    }

    /** A space finishes the name, and everything after it is the argument. */
    @Test
    fun argumentOfADraft() {
        val empty = assertNotNull(SlashDraft.parse("/review "))
        assertEquals("review", empty.name)
        assertTrue(empty.isComplete)
        assertNull(empty.argument, "a trailing space is where the argument goes, not an argument")

        val written = assertNotNull(SlashDraft.parse("/review  focus on the retry logic "))
        assertEquals("review", written.name)
        assertTrue(written.isComplete)
        assertEquals("focus on the retry logic", written.argument)
    }

    /** A command's own output is drawn at Simple, and the agent's working is not. */
    @Test
    fun commandOutputSurvivesTheSimpleLevel() {
        fun entry(tool: String): TimelineEntry {
            val timeline = Timeline()
            timeline.apply(SessionEvent(seq = 1, ts = 0, kind = SessionEvent.toolCallKind, blockID = "b",
                                        body = SessionEventBody.ToolCall(ToolCallPayload(tool = tool, kind = ToolKind.other,
                                                                                         title = tool,
                                                                                         status = ToolStatus.succeeded,
                                                                                         output = "…"))))
            return timeline.entries[0]
        }
        assertTrue(entry(tool = "/usage").isDrawn(at = TimelineDetail.simple), "the reader asked for this by name, so Simple keeps it")
        assertTrue(entry(tool = "/usage").isDrawn(at = TimelineDetail.detailed))
        assertFalse(entry(tool = "Bash").isDrawn(at = TimelineDetail.simple), "an ordinary tool call is still the agent's working")
    }
}
