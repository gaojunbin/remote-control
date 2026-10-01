package com.junbingao.remotecontrol.win.chat

import com.junbingao.remotecontrol.win.chat.markdown.ChatMarkdown
import com.junbingao.remotecontrol.win.chat.markdown.HighlightStyle
import com.junbingao.remotecontrol.win.chat.markdown.HighlightTheme
import com.junbingao.remotecontrol.win.chat.markdown.MDBlock
import com.junbingao.remotecontrol.win.chat.markdown.MDCell
import com.junbingao.remotecontrol.win.chat.markdown.MDCode
import com.junbingao.remotecontrol.win.chat.markdown.MDCodeRun
import com.junbingao.remotecontrol.win.chat.markdown.MDInline
import com.junbingao.remotecontrol.win.chat.markdown.MDInlines
import com.junbingao.remotecontrol.win.chat.markdown.MDMarks
import com.junbingao.remotecontrol.win.chat.markdown.MDStack
import com.junbingao.remotecontrol.win.chat.markdown.MDText
import com.junbingao.remotecontrol.win.chat.markdown.MDWeight
import com.junbingao.remotecontrol.win.chat.markdown.document
import com.junbingao.remotecontrol.win.platform.MarkdownEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The web's own pipeline, run from the bundle, and what the chat makes of the tree it returns: the
 * blocks `.md` lays out and the gaps its margins collapse to (`chat.css` § markdown), and
 * github.css's colours.
 */
class ChatMarkdownTests {
    private fun blocks(text: String): MDStack? = (MarkdownEngine.shared.document(text) as? ChatMarkdown.Blocks)?.stack

    private fun text(block: MDBlock?): MDText? = (block?.kind as? MDBlock.Kind.Text)?.text

    private fun code(markdown: String): MDCode? = (blocks(markdown)?.blocks?.firstOrNull()?.kind as? MDBlock.Kind.Code)?.code

    @Test
    fun theBundleLoadsAndParses() {
        assertNotNull(blocks("hello"))
    }

    @Test
    fun aHeadingIsSetInItsOwnTypeAndMeetsTheParagraphAtItsBottomMargin() {
        val stack = blocks("## Plan\n\nThe **lock** holds.")
        assertEquals(2, stack?.blocks?.size)
        assertEquals(15f, text(stack?.blocks?.first())?.size)
        assertEquals(MDWeight.semibold, text(stack?.blocks?.first())?.weight)
        assertEquals(listOf(0f, 8f), stack?.gaps)
        assertEquals(
            listOf(MDInline.Text("The ", MDMarks()), MDInline.Text("lock", MDMarks(weight = MDWeight.bold)), MDInline.Text(" holds.", MDMarks())),
            text(stack?.blocks?.last())?.inlines,
        )
    }

    @Test
    fun aRuleKeepsSixteenEitherSide() {
        val stack = blocks("a\n\n---\n\nb")
        assertEquals(listOf(0f, 16f, 16f), stack?.gaps)
        assertEquals(0f, stack?.top)
        assertEquals(0f, stack?.bottom)
    }

    @Test
    fun aNestedListIsItsItemsSecondBlock() {
        val list = (blocks("1. a\n2. b\n   - c")?.blocks?.firstOrNull()?.kind as? MDBlock.Kind.List)?.list ?: fail("no list")
        assertTrue(list.ordered)
        assertEquals(1, list.start)
        assertEquals(0, list.depth)
        assertEquals(2, list.items.size)
        // The nested list's 12 below collapses through the item's own 4, as nothing on the `li`
        // stops it.
        assertEquals(listOf(4f, 12f), list.items.map { it.bottom })
        val nested = (list.items[1].blocks.last().kind as? MDBlock.Kind.List)?.list ?: fail("no nested list")
        assertFalse(nested.ordered)
        assertEquals(1, nested.depth)
        assertEquals(listOf(0f, 0f), list.items[1].gaps)
    }

    @Test
    fun anOrderedListStartsWhereItsFirstNumberSays() {
        val list = (blocks("3. x\n4. y")?.blocks?.firstOrNull()?.kind as? MDBlock.Kind.List)?.list ?: fail("no list")
        assertEquals(3, list.start)
    }

    @Test
    fun aTaskListItemStartsWithItsCheckbox() {
        val list = (blocks("- [x] done\n- [ ] todo")?.blocks?.firstOrNull()?.kind as? MDBlock.Kind.List)?.list ?: fail("no list")
        assertEquals(listOf(MDInline.Checkbox(checked = true), MDInline.Text(" done", MDMarks())), text(list.items.first().blocks.first())?.inlines)
        assertEquals(listOf(MDInline.Checkbox(checked = false), MDInline.Text(" todo", MDMarks())), text(list.items.last().blocks.first())?.inlines)
    }

    @Test
    fun aTablesCellsTakeTheirColumnsAlignment() {
        val table = (blocks("| a | b | c |\n| --- | ---: | :---: |\n| 1 | 2 | 3 |")?.blocks?.firstOrNull()?.kind as? MDBlock.Kind.Table)?.table
            ?: fail("no table")
        assertEquals(3, table.columns)
        assertEquals(listOf(MDCell.Align.leading, MDCell.Align.trailing, MDCell.Align.center), table.header.map { it.align })
        assertEquals(listOf(true, true, true), table.header.map { it.header })
        assertEquals(1, table.rows.size)
        assertEquals(
            listOf(listOf(MDInline.Text("1", MDMarks())), listOf(MDInline.Text("2", MDMarks())), listOf(MDInline.Text("3", MDMarks()))),
            table.rows.first().map { it.inlines },
        )
    }

    @Test
    fun strikethroughAndBareLinksAreMarked() {
        val inlines = text(blocks("x ~~y~~ https://example.com/runs/42")?.blocks?.firstOrNull())?.inlines
        assertEquals(
            listOf(
                MDInline.Text("x ", MDMarks()),
                MDInline.Text("y", MDMarks(strike = true)),
                MDInline.Text(" ", MDMarks()),
                MDInline.Text("https://example.com/runs/42", MDMarks(link = "https://example.com/runs/42")),
            ),
            inlines,
        )
    }

    @Test
    fun aHighlightedBlockIsColouredAsGithubCSSColoursIt() {
        val block = code(
            "```python\ndef refresh(self, token: str) -> Token:\n" +
                "    with self._lock:  # one refresh at a time\n        return self._rotate(token)\n```",
        )
        assertEquals(true, block?.highlighted)
        assertEquals(3, block?.lines?.size)
        fun color(word: String): Int? = block?.lines?.flatten()?.firstOrNull { it.text == word }?.style?.color
        assertEquals(0xD73A49, color("def"))
        assertEquals(0x6F42C1, color("refresh"))
        assertEquals(0xE36209, color("str"))
        // `.hljs-variable.language_` outranks `.hljs-variable` though it comes first.
        assertEquals(0xD73A49, color("self"))
        assertEquals(0x6A737D, color("# one refresh at a time"))
        assertEquals(0x24292E, color("._rotate(token)"))
        assertEquals(true, block?.source?.endsWith("return self._rotate(token)\n"))
    }

    @Test
    fun aLanguageHighlightJSDoesNotKnowIsStillAnHljsBlock() {
        val block = code("```nosuchlang\nx = 1\n```")
        assertEquals(true, block?.highlighted)
        assertEquals(listOf(listOf(MDCodeRun("x = 1", HighlightTheme.base))), block?.lines)
    }

    @Test
    fun aBlockWithNoLanguageKeepsThePagesInk() {
        val block = code("```\nplain\n```")
        assertEquals(false, block?.highlighted)
        assertEquals(0x111111, block?.lines?.firstOrNull()?.firstOrNull()?.style?.color)
    }

    @Test
    fun aDiffBlockTintsItsLines() {
        val block = code("```diff\n+new\n-old\n```")
        assertEquals(listOf(0xF0FFF4, 0xFFEEF0), block?.lines?.map { it.firstOrNull()?.style?.background })
    }
}

/** `white-space: normal` over inline content, and CSS's `bolder`. */
class ChatMarkdownInlineTests {
    private val plain = MDMarks()
    private val bold = MDMarks(weight = MDWeight.bold)

    @Test
    fun aRunOfWhiteSpaceIsOneSpaceAndNoneAtTheEnds() {
        assertEquals(listOf(MDInline.Text("a b", plain)), MDInlines.collapse(listOf(MDInline.Text("  a \n\t b  ", plain))))
    }

    @Test
    fun aSpaceCollapsesAcrossTheEdgeOfAnElement() {
        assertEquals(
            listOf(MDInline.Text("a ", plain), MDInline.Text("b", bold)),
            MDInlines.collapse(listOf(MDInline.Text("a ", plain), MDInline.Text(" b", bold))),
        )
    }

    @Test
    fun aLineBreakTakesTheSpacesEitherSideOfIt() {
        assertEquals(
            listOf(MDInline.Text("a", plain), MDInline.LineBreak, MDInline.Text("b", plain)),
            MDInlines.collapse(listOf(MDInline.Text("a ", plain), MDInline.LineBreak, MDInline.Text(" b", plain))),
        )
    }

    @Test
    fun adjoiningRunsWithTheSameMarksAreOne() {
        assertEquals(listOf(MDInline.Text("ab", plain)), MDInlines.collapse(listOf(MDInline.Text("a", plain), MDInline.Text("b", plain))))
    }

    @Test
    fun bolderIsCSSBolder() {
        assertEquals(MDWeight.bold, MDWeight.regular.bolder)
        assertEquals(MDWeight.bold, MDWeight.medium.bolder)
        assertEquals(MDWeight.black, MDWeight.semibold.bolder)
    }

    @Test
    fun aSpanWithoutARuleInheritsAndATintIsKept() {
        val inherited = HighlightStyle(color = 0x6F42C1)
        assertEquals(inherited, HighlightTheme.style(classes = listOf("hljs-params"), ancestors = listOf(listOf("hljs-function")), inherited = inherited))
        val tinted = HighlightTheme.style(classes = listOf("hljs-addition"), ancestors = emptyList(), inherited = HighlightTheme.base)
        assertEquals(HighlightStyle(color = 0x22863A, background = 0xF0FFF4), tinted)
        assertEquals(
            0xD73A49,
            HighlightTheme.style(classes = listOf("hljs-keyword"), ancestors = listOf(listOf("hljs-meta")), inherited = HighlightStyle(color = 0x005CC5)).color,
        )
        assertTrue(HighlightTheme.style(classes = listOf("hljs-section"), ancestors = emptyList(), inherited = HighlightTheme.base).bold)
    }
}
