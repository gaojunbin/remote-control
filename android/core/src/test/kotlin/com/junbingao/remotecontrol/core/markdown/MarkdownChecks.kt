package com.junbingao.remotecontrol.core.markdown

import com.junbingao.remotecontrol.core.CheckRunner
import java.net.URI
import kotlin.test.Test

/** `ios/Verification/MarkdownChecks.swift`, and the parser's rules beyond it. */
class MarkdownChecks {
    @Test
    fun blocks() {
        val checks = CheckRunner("markdown")
        val document = MarkdownDocument(
            """
            # Heading

            A paragraph that
            wraps.

            > quoted

            ---
            """.trimIndent())
        checks.equal(document.blocks.size, 4, "heading, paragraph, quote and rule parse")
        val heading = document.blocks.getOrNull(0)?.content as? MarkdownBlock.Content.Heading
        checks.equal(heading?.level, 1, "an ATX heading keeps its level")
        checks.equal(heading?.text, "Heading", "an ATX heading keeps its text")
        val paragraph = document.blocks.getOrNull(1)?.content as? MarkdownBlock.Content.Paragraph
        checks.expect(paragraph?.text?.contains("\n") == true, "a soft-wrapped paragraph keeps its line break")
        checks.expect(document.blocks.getOrNull(2)?.content is MarkdownBlock.Content.Quote, "the third block is a quote")
        checks.expect(document.blocks.getOrNull(3)?.content == MarkdownBlock.Content.ThematicBreak,
                      "the fourth block is a thematic break")

        checks.equal(MarkdownDocument("").blocks.size, 0, "an empty document has no blocks")
        checks.equal(MarkdownDocument("a\r\nb").blocks.size, 1, "CRLF normalises to one paragraph")
        checks.assertAll()
    }

    @Test
    fun code() {
        val checks = CheckRunner("markdown")
        val closed = MarkdownDocument("```swift\nlet x = 1\n```").blocks.firstOrNull()?.content as? MarkdownBlock.Content.Code
        checks.equal(closed?.language, "swift", "the fence language is captured")
        checks.equal(closed?.source, "let x = 1", "the fence body is captured")
        checks.equal(closed?.closed, true, "a closed fence is marked closed")
        val streaming = MarkdownDocument("```python\nprint(1)").blocks.firstOrNull()?.content as? MarkdownBlock.Content.Code
        checks.equal(streaming?.closed, false, "an unterminated fence is marked open, for a streaming answer")
        checks.expect(MarkdownDocument("    indented code\n").blocks.firstOrNull()?.content is MarkdownBlock.Content.Code,
                      "an indented block parses as code")
        checks.assertAll()
    }

    @Test
    fun tables() {
        val checks = CheckRunner("markdown")
        val table = (MarkdownDocument(
            """
            | Name | Count |
            | :--- | ----: |
            | a | 1 |
            | b | 2 |
            """.trimIndent()).blocks.firstOrNull()?.content as? MarkdownBlock.Content.Table)?.table
        checks.equal(table?.headers, listOf("Name", "Count"), "table headers parse")
        checks.equal(table?.alignments, listOf(MarkdownTable.Alignment.leading, MarkdownTable.Alignment.trailing),
                     "column alignment parses")
        checks.equal(table?.rows?.size, 2, "table rows parse")
        checks.assertAll()
    }

    @Test
    fun lists() {
        val checks = CheckRunner("markdown")
        val tasks = MarkdownDocument(
            """
            - [x] done
            - [ ] pending
              - nested
            """.trimIndent()).blocks.firstOrNull()?.content as? MarkdownBlock.Content.List
        checks.equal(tasks?.ordered, false, "a bullet list is unordered")
        checks.equal(tasks?.items?.size, 2, "task list items parse")
        checks.equal(tasks?.items?.getOrNull(0)?.checked, true, "a checked task parses")
        checks.equal(tasks?.items?.getOrNull(1)?.checked, false, "an unchecked task parses")
        val nested = tasks?.items?.getOrNull(1)?.blocks?.lastOrNull()?.content as? MarkdownBlock.Content.List
        checks.equal(nested?.items?.size, 1, "and the indented item nests under the one above it")
        val numbered = MarkdownDocument("1. first\n2. second").blocks.firstOrNull()?.content as? MarkdownBlock.Content.List
        checks.equal(numbered?.ordered, true, "a numbered list is ordered")
        checks.equal(numbered?.items?.size, 2, "numbered items parse")
        checks.equal(numbered?.items?.map { it.number }, listOf(1, 2), "and keeps its numbers")
        checks.assertAll()
    }

    @Test
    fun math() {
        val checks = CheckRunner("markdown")
        checks.equal(MarkdownMath.spans("the total is $12.50 today").count { it.isMath }, 0,
                     "a currency amount is not treated as math")
        val spans = MarkdownMath.spans("the value \$x + y\$ matters")
        checks.equal(spans.count { it.isMath }, 1, "one math span is found")
        checks.equal(spans.firstOrNull { it.isMath }?.text, "x + y", "the math span is extracted")
        checks.equal(MarkdownMath.spans("`\$not math\$`").count { it.isMath }, 0, "a code span is never math")
        checks.expect(MarkdownMath.spans("\$\$E = mc^2\$\$").firstOrNull { it.isMath }?.display == true,
                      "display math is recognised")
        checks.assertAll()
    }

    @Test
    fun links() {
        val checks = CheckRunner("markdown")
        checks.equal(MarkdownLink.filePath(URI("src/main.swift:42")), "src/main.swift",
                     "an editor line suffix is not part of the path")
        checks.expect(MarkdownLink.filePath(URI("https://example.com")) == null, "a web URL is not a file path")
        checks.equal(MarkdownLink.filePath(URI("file:///Users/me/app.kt:12:3")), "/Users/me/app.kt",
                     "a file URL's line and column are dropped too")
        checks.assertAll()
    }

    /** Beyond RCCore's checks: the rest of the grammar, each answer the one RCCore's parser gives. */
    @Test
    fun grammar() {
        val checks = CheckRunner("markdown")
        val setext = MarkdownDocument("Title\n=====\n\nSub\n---").blocks.map { it.content }
        checks.equal(setext, listOf(MarkdownBlock.Content.Heading(1, "Title"), MarkdownBlock.Content.Heading(2, "Sub")),
                     "setext headings take their level from the underline")
        checks.equal((MarkdownDocument("## Closed ##").blocks.firstOrNull()?.content as? MarkdownBlock.Content.Heading)?.text,
                     "Closed", "closing hashes are not part of a heading")
        checks.expect(MarkdownDocument("#hashtag").blocks.firstOrNull()?.content is MarkdownBlock.Content.Paragraph,
                      "a hash without a space is a paragraph")
        val math = MarkdownDocument("$$\na + b\n$$").blocks.firstOrNull()?.content
        checks.equal(math, MarkdownBlock.Content.Math("a + b"), "a display math block spans its lines")
        checks.equal(MarkdownDocument("\\[x\\]").blocks.firstOrNull()?.content, MarkdownBlock.Content.Math("x"),
                     "and a one-line bracketed block is math")
        val fence = MarkdownDocument("~~~~ Kotlin extra\ncode\n~~~\n~~~~").blocks.firstOrNull()?.content as? MarkdownBlock.Content.Code
        checks.equal(fence?.language, "kotlin", "a fence's language is its first word, lower-cased")
        checks.equal(fence?.source, "code\n~~~", "and a shorter fence does not close it")
        val quote = MarkdownDocument("> one\n> > two").blocks.firstOrNull()?.content as? MarkdownBlock.Content.Quote
        checks.expect(quote?.blocks?.lastOrNull()?.content is MarkdownBlock.Content.Quote, "quotes nest")
        val ids = MarkdownDocument("para\n\n- a\n- b").blocks
        checks.equal(ids.map { it.id }, listOf("md-0", "md-2"), "a block's id is its scope and its first line")
        checks.equal((ids.lastOrNull()?.content as? MarkdownBlock.Content.List)?.items?.map { it.id },
                     listOf("md-item2", "md-item3"), "and an item's id is its scope and its line")
        checks.equal(MarkdownDocument("a | b\n--- | ---\n1 | 2 | 3").blocks.firstOrNull()?.content,
                     MarkdownBlock.Content.Table(MarkdownTable(listOf("a", "b"),
                                                               listOf(MarkdownTable.Alignment.leading, MarkdownTable.Alignment.leading),
                                                               listOf(listOf("1", "2")))),
                     "a row wider than the header is cut to it")
        checks.equal(MarkdownMath.spans("\\(a\\) and \\\$5").map { it.text to it.isMath },
                     listOf("a" to true, " and \\$5" to false), "escaped delimiters and dollars stay text")
        checks.equal(MarkdownMath.spans("\$3 and \$4").count { it.isMath }, 0, "two prices are not one formula")
        checks.equal(MarkdownMath.spans("\$x\$5").count { it.isMath }, 0, "a formula glued to a digit is a price")
        checks.expect(MarkdownDocument("stop", isCancelled = { true }).blocks.isEmpty(), "a parse told to stop returns nothing")
        checks.assertAll()
    }
}
