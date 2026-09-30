package com.junbingao.remotecontrol.core.markdown

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.arrayValue
import com.junbingao.remotecontrol.core.protocol.get
import com.junbingao.remotecontrol.core.protocol.jsonOf
import com.junbingao.remotecontrol.core.protocol.stringValue
import kotlinx.serialization.json.JsonElement
import java.net.URI
import kotlin.test.Test

/**
 * The parser against RCCore's own: `rccore-parses.json` holds what RCCore's `MarkdownDocument`,
 * `MarkdownMath` and `MarkdownLink` made of each input, recorded by running the Swift parser over
 * them (round 56). Every input here must come out of this port as the same tree.
 */
class MarkdownParityTests {
    private val cases: List<JsonElement> = JSONValue.parse(
        checkNotNull(javaClass.getResourceAsStream("/markdown/rccore-parses.json")).readBytes()).arrayValue.orEmpty()

    @Test
    fun documentsParseAsRCCoreParsesThem() {
        val checks = CheckRunner("markdown")
        val documents = cases.mapNotNull { case -> case["document"]?.stringValue?.let { it to case["blocks"] } }
        checks.expect(documents.size > 80, "the reference holds the documents (${documents.size})")
        for ((document, expected) in documents) {
            checks.equal(jsonOf(tree(MarkdownDocument(document).blocks)), expected, "the parse of ${document.take(40).quoted()}")
        }
        checks.assertAll()
    }

    @Test
    fun mathSpansAsRCCoreFindsThem() {
        val checks = CheckRunner("markdown")
        for (case in cases) {
            val source = case["math"]?.stringValue ?: continue
            val spans = MarkdownMath.spans(source).map {
                mapOf("display" to it.display, "id" to it.id, "isMath" to it.isMath, "text" to it.text)
            }
            checks.equal(jsonOf(spans), case["spans"], "the spans of ${source.quoted()}")
        }
        checks.assertAll()
    }

    @Test
    fun linksAsRCCoreReadsThem() {
        val checks = CheckRunner("markdown")
        for (case in cases) {
            val link = case["link"]?.stringValue ?: continue
            checks.equal(jsonOf(MarkdownLink.filePath(URI(link))), case["path"], "the path of ${link.quoted()}")
        }
        checks.assertAll()
    }

    private fun tree(blocks: List<MarkdownBlock>): List<Map<String, Any?>> = blocks.map { block ->
        val node = sortedMapOf<String, Any?>("id" to block.id, "line" to block.sourceLine)
        when (val content = block.content) {
            is MarkdownBlock.Content.Paragraph -> node += mapOf("kind" to "paragraph", "text" to content.text)
            is MarkdownBlock.Content.Heading -> node += mapOf("kind" to "heading", "level" to content.level, "text" to content.text)
            is MarkdownBlock.Content.Code -> node += mapOf("kind" to "code", "language" to content.language,
                                                           "source" to content.source, "closed" to content.closed)
            is MarkdownBlock.Content.Quote -> node += mapOf("kind" to "quote", "blocks" to tree(content.blocks))
            is MarkdownBlock.Content.List -> node += mapOf("kind" to "list", "ordered" to content.ordered, "items" to content.items.map {
                mapOf("id" to it.id, "number" to it.number, "checked" to it.checked, "blocks" to tree(it.blocks))
            })
            is MarkdownBlock.Content.Table -> node += mapOf("kind" to "table", "headers" to content.table.headers,
                                                            "alignments" to content.table.alignments.map { it.rawValue },
                                                            "rows" to content.table.rows)
            MarkdownBlock.Content.ThematicBreak -> node["kind"] = "rule"
            is MarkdownBlock.Content.Math -> node += mapOf("kind" to "math", "text" to content.text)
        }
        node
    }

    private fun String.quoted(): String = "\"" + replace("\n", "\\n").replace("\t", "\\t") + "\""
}
