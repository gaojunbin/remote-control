package com.junbingao.remotecontrol.android.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The inline Markdown an agent's message is drawn with, read the way `AttributedString(markdown:)`
 * with `.inlineOnlyPreservingWhitespace` reads it on the iPhone.
 */
class ChatMarkdownInlineTest {
    private fun runs(source: String) = MarkdownInlineParser.runs(source)

    @Test
    fun emphasisAndStrongEmphasisAreRunsOfTheirOwn() {
        assertEquals(
            listOf(MarkdownRun("bold", strong = true), MarkdownRun(" and "), MarkdownRun("leaning", em = true)),
            runs("**bold** and *leaning*"),
        )
        assertEquals("the rule of three nests them", listOf(MarkdownRun("both", strong = true, em = true)), runs("***both***"))
        assertEquals(listOf(MarkdownRun("gone", strike = true)), runs("~~gone~~"))
    }

    @Test
    fun whatIsNotEmphasisStaysAsItWasTyped() {
        assertEquals("an underscore inside a word is part of it", listOf(MarkdownRun("snake_case_names")), runs("snake_case_names"))
        assertEquals("a star with space on both sides is a star", listOf(MarkdownRun("2 * 3 * 4")), runs("2 * 3 * 4"))
        assertEquals("while one inside a word leans, as CommonMark has it", listOf(MarkdownRun("2"), MarkdownRun("3", em = true), MarkdownRun("4")), runs("2*3*4"))
        assertEquals("a delimiter that never closes is what was typed", listOf(MarkdownRun("**open")), runs("**open"))
        assertEquals("an escape is the character itself", listOf(MarkdownRun("*not leaning*")), runs("\\*not leaning\\*"))
    }

    @Test
    fun aCodeSpanKeepsWhatIsInside() {
        assertEquals(listOf(MarkdownRun("run "), MarkdownRun("a*b*c", code = true)), runs("run `a*b*c`"))
        assertEquals("a longer fence holds a backtick", listOf(MarkdownRun("x ` y", code = true)), runs("`` x ` y ``"))
    }

    @Test
    fun linksCarryTheirDestinationAndAnImageIsItsDescription() {
        assertEquals(listOf(MarkdownRun("the docs", link = "https://example.com/docs")), runs("[the docs](https://example.com/docs)"))
        assertEquals(listOf(MarkdownRun("https://example.com", link = "https://example.com")), runs("<https://example.com>"))
        assertEquals("an image loads nothing", listOf(MarkdownRun("a diagram")), runs("![a diagram](diagram.png)"))
    }

    @Test
    fun entitiesAndWhitespaceAreKept() {
        assertEquals(listOf(MarkdownRun("fish & chips")), runs("fish &amp; chips"))
        assertEquals("every space and line break stays where it was", listOf(MarkdownRun("two  spaces\nand a line")), runs("two  spaces\nand a line"))
    }
}
