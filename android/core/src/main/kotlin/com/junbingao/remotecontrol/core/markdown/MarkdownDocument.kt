package com.junbingao.remotecontrol.core.markdown

import com.junbingao.remotecontrol.core.isSwiftNumber
import com.junbingao.remotecontrol.core.isSwiftWhitespace
import com.junbingao.remotecontrol.core.scalars
import com.junbingao.remotecontrol.core.text
import java.net.URI

/**
 * Bounded, deterministic block parser for the Markdown emitted by coding agents. It leaves inline
 * styling to the screen and never evaluates HTML or loads URLs.
 *
 * RCCore's parser checks `Task.isCancelled` as it goes; here the caller says the same thing
 * through [isCancelled], so a parse a newer source overtook can stop early. A parse told to stop
 * returns what it had, which is only ever a prefix of the document.
 */
class MarkdownDocument(source: String, isCancelled: () -> Boolean = { false }) {
    val blocks: List<MarkdownBlock>

    init {
        val normalized = source.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n").mapIndexed { index, text -> MarkdownLine(text = text, number = index) }
        blocks = MarkdownParser(isCancelled).parse(lines, scope = "md", depth = 0)
    }

    override fun equals(other: Any?): Boolean = other is MarkdownDocument && blocks == other.blocks

    override fun hashCode(): Int = blocks.hashCode()

    override fun toString(): String = "MarkdownDocument(blocks=$blocks)"
}

data class MarkdownBlock(val id: String, val sourceLine: Int, val content: Content) {
    sealed interface Content {
        data class Paragraph(val text: String) : Content
        data class Heading(val level: Int, val text: String) : Content
        data class Code(val language: String, val source: String, val closed: Boolean) : Content
        data class Quote(val blocks: kotlin.collections.List<MarkdownBlock>) : Content
        data class List(val ordered: Boolean, val items: kotlin.collections.List<MarkdownListItem>) : Content
        data class Table(val table: MarkdownTable) : Content
        data object ThematicBreak : Content
        data class Math(val text: String) : Content
    }
}

data class MarkdownListItem(val id: String, val number: Int?, val checked: Boolean?, val blocks: List<MarkdownBlock>)

data class MarkdownTable(val headers: List<String>, val alignments: List<Alignment>, val rows: List<List<String>>) {
    enum class Alignment(val rawValue: String) { leading("leading"), center("center"), trailing("trailing") }
}

data class MarkdownMathSpan(val id: Int, val text: String, val isMath: Boolean, val display: Boolean)

object MarkdownLink {
    private val lineSuffix = Regex(":[0-9]+(?::[0-9]+)?$")

    /** Remote file annotations use editor line suffixes; they are not part of the path. */
    fun filePath(url: URI): String? {
        if (url.scheme != null && url.scheme.lowercase() != "file") return null
        val path = url.path ?: return null
        if (path.isEmpty()) return null
        return path.replace(lineSuffix, "")
    }
}

/** Complete inline math only. Escaped dollar signs, code spans and currency stay text. */
object MarkdownMath {
    fun spans(source: String): List<MarkdownMathSpan> {
        val chars = source.scalars()
        val result = mutableListOf<MarkdownMathSpan>()
        val buffer = StringBuilder()
        var index = 0
        var codeTicks = 0
        fun flush() {
            if (buffer.isNotEmpty()) {
                result.add(MarkdownMathSpan(id = result.size, text = buffer.toString(), isMath = false, display = false))
                buffer.clear()
            }
        }
        while (index < chars.size) {
            if (chars[index] == '`'.code) {
                var end = index
                while (end < chars.size && chars[end] == '`'.code) end += 1
                val run = end - index
                if (codeTicks == 0) codeTicks = run else if (run == codeTicks) codeTicks = 0
                buffer.append(chars.text(index, end))
                index = end
                continue
            }
            if (codeTicks == 0) {
                var opener = 0
                var closer = ""
                var display = false
                if (chars[index] == '\\'.code && index + 1 < chars.size &&
                    (chars[index + 1] == '('.code || chars[index + 1] == '['.code)) {
                    opener = 2
                    display = chars[index + 1] == '['.code
                    closer = if (display) "\\]" else "\\)"
                } else if (chars[index] == '$'.code && index + 1 < chars.size && !isSwiftWhitespace(chars[index + 1])) {
                    display = chars[index + 1] == '$'.code
                    opener = if (display) 2 else 1
                    closer = if (display) "$$" else "$"
                }
                if (opener > 0) {
                    val closing = closer.scalars()
                    var end = index + opener
                    while (end + closing.size <= chars.size) {
                        if (chars[end] == '\\'.code && closing.first() != '\\'.code) {
                            end += 2
                            continue
                        }
                        if (chars.copyOfRange(end, end + closing.size).contentEquals(closing)) {
                            val body = chars.copyOfRange(index + opener, end)
                            val currency = !display && body.isNotEmpty() &&
                                body.all { isSwiftNumber(it) || it == '.'.code || it == ','.code }
                            val trailingDigit = end + closing.size < chars.size && isSwiftNumber(chars[end + closing.size])
                            if (body.isNotEmpty() && (!isSwiftWhitespace(body.last()) || opener == 2) && !currency && !trailingDigit) {
                                flush()
                                result.add(MarkdownMathSpan(id = result.size, text = body.text(), isMath = true, display = display))
                                index = end + closing.size
                                break
                            }
                        }
                        end += 1
                    }
                    if (index == end + closing.size) continue
                }
            }
            if (chars[index] == '\\'.code && index + 1 < chars.size) {
                buffer.appendCodePoint(chars[index]).appendCodePoint(chars[index + 1])
                index += 2
            } else {
                buffer.appendCodePoint(chars[index])
                index += 1
            }
        }
        flush()
        return result
    }
}
