package com.junbingao.remotecontrol.core.markdown

import com.junbingao.remotecontrol.core.isSwiftWhitespace
import com.junbingao.remotecontrol.core.scalars
import com.junbingao.remotecontrol.core.text
import com.junbingao.remotecontrol.core.trimmingWhitespaces
import com.junbingao.remotecontrol.core.trimmingWhitespacesAndNewlines

// The block grammar behind `MarkdownDocument`, split from `MarkdownDocument.swift` for its length.

internal data class MarkdownLine(val text: String, val number: Int)

/**
 * The block grammar. RCCore reads it over Swift characters; this reads it over Unicode scalars,
 * which differ only where a combining mark is attached directly to a Markdown marker.
 */
internal class MarkdownParser(private val isCancelled: () -> Boolean) {
    private class Fence(val marker: Int, val count: Int, val language: String)
    private class Marker(val indent: Int, val contentIndent: Int, val number: Int?, val body: String, val checked: Boolean?)

    fun parse(lines: List<MarkdownLine>, scope: String, depth: Int): List<MarkdownBlock> {
        if (isCancelled()) return emptyList()
        if (depth >= 12) {
            val first = lines.firstOrNull() ?: return emptyList()
            return listOf(MarkdownBlock(id = "$scope-${first.number}", sourceLine = first.number,
                                        content = MarkdownBlock.Content.Paragraph(lines.joinToString("\n") { it.text })))
        }
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0
        fun add(content: MarkdownBlock.Content, index: Int) {
            blocks.add(MarkdownBlock(id = "$scope-${lines[index].number}", sourceLine = lines[index].number, content = content))
        }
        while (i < lines.size) {
            if (isCancelled()) break
            val line = lines[i].text
            val trimmed = line.trimmingWhitespaces()
            if (trimmed.isEmpty()) {
                i += 1
                continue
            }
            val start = i
            val fence = fence(line)
            if (fence != null) {
                i += 1
                val source = mutableListOf<String>()
                var closed = false
                while (i < lines.size) {
                    if (closes(lines[i].text, fence)) {
                        closed = true
                        i += 1
                        break
                    }
                    source.add(lines[i].text)
                    i += 1
                }
                add(MarkdownBlock.Content.Code(language = fence.language, source = source.joinToString("\n"), closed = closed), start)
                continue
            }
            if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
                val opener = if (trimmed.startsWith("$$")) "$$" else "\\["
                val closer = if (trimmed.startsWith("$$")) "$$" else "\\]"
                var content = trimmed.substring(opener.length)
                if (content.endsWith(closer)) {
                    content = content.dropLast(closer.length)
                    i += 1
                    add(MarkdownBlock.Content.Math(content.trimmingWhitespacesAndNewlines()), start)
                    continue
                }
                var end = i + 1
                while (end < lines.size && !lines[end].text.trimmingWhitespaces().endsWith(closer)) end += 1
                if (end < lines.size) {
                    val parts = mutableListOf(content)
                    for (index in i + 1 until end) parts.add(lines[index].text)
                    parts.add(lines[end].text.trimmingWhitespaces().dropLast(closer.length))
                    i = end + 1
                    add(MarkdownBlock.Content.Math(parts.joinToString("\n").trimmingWhitespacesAndNewlines()), start)
                    continue
                }
            }
            val heading = heading(line)
            if (heading != null) {
                add(MarkdownBlock.Content.Heading(level = heading.first, text = heading.second), start)
                i += 1
                continue
            }
            if (!rule(line) && marker(line) == null && quote(line) == null && i + 1 < lines.size) {
                val level = setext(lines[i + 1].text)
                if (level != null) {
                    add(MarkdownBlock.Content.Heading(level = level, text = trimmed), start)
                    i += 2
                    continue
                }
            }
            if (rule(line)) {
                add(MarkdownBlock.Content.ThematicBreak, start)
                i += 1
                continue
            }
            if (quote(line) != null) {
                val quoted = mutableListOf<MarkdownLine>()
                while (i < lines.size) {
                    val value = quote(lines[i].text) ?: break
                    quoted.add(MarkdownLine(text = value, number = lines[i].number))
                    i += 1
                }
                add(MarkdownBlock.Content.Quote(parse(quoted, scope = "$scope-q${lines[start].number}", depth = depth + 1)), start)
                continue
            }
            val first = marker(line)
            if (first != null && first.indent <= 3) {
                val items = mutableListOf<MarkdownListItem>()
                val ordered = first.number != null
                while (i < lines.size) {
                    val item = marker(lines[i].text) ?: break
                    if (item.indent != first.indent || (item.number != null) != ordered) break
                    val itemLine = lines[i].number
                    val children = mutableListOf(MarkdownLine(text = item.body, number = itemLine))
                    i += 1
                    var previousBlank = false
                    while (i < lines.size) {
                        val next = lines[i].text
                        val sibling = marker(next)
                        if (sibling != null && sibling.indent == first.indent) break
                        if (next.trimmingWhitespaces().isEmpty()) {
                            if (i + 1 < lines.size) {
                                val following = marker(lines[i + 1].text)
                                if (following != null && following.indent == first.indent) {
                                    i += 1
                                    break
                                }
                            }
                            children.add(lines[i])
                            i += 1
                            previousBlank = true
                            continue
                        }
                        val indent = indentation(next)
                        if (indent < item.contentIndent) {
                            if (previousBlank || startsBlock(lines, i)) break
                            children.add(lines[i])
                            i += 1
                        } else {
                            children.add(MarkdownLine(text = removingIndent(next, columns = item.contentIndent), number = lines[i].number))
                            i += 1
                        }
                        previousBlank = false
                    }
                    items.add(MarkdownListItem(id = "$scope-item$itemLine", number = item.number, checked = item.checked,
                                               blocks = parse(children, scope = "$scope-li$itemLine", depth = depth + 1)))
                }
                add(MarkdownBlock.Content.List(ordered = ordered, items = items), start)
                continue
            }
            if (i + 1 < lines.size) {
                val table = tableHeader(line, lines[i + 1].text)
                if (table != null) {
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].text.trimmingWhitespaces().isNotEmpty() && containsTablePipe(lines[i].text)) {
                        var row = tableCells(lines[i].text)
                        if (row.size < table.first.size) row = row + List(table.first.size - row.size) { "" }
                        rows.add(row.take(table.first.size))
                        i += 1
                    }
                    add(MarkdownBlock.Content.Table(MarkdownTable(headers = table.first, alignments = table.second, rows = rows)), start)
                    continue
                }
            }
            if (indentation(line) >= 4) {
                val source = mutableListOf<String>()
                while (i < lines.size && (indentation(lines[i].text) >= 4 || lines[i].text.trimmingWhitespaces().isEmpty())) {
                    source.add(removingIndent(lines[i].text, columns = 4))
                    i += 1
                }
                while (source.lastOrNull() == "") source.removeAt(source.size - 1)
                add(MarkdownBlock.Content.Code(language = "", source = source.joinToString("\n"), closed = true), start)
                continue
            }
            val paragraph = mutableListOf(line)
            i += 1
            while (i < lines.size && lines[i].text.trimmingWhitespaces().isNotEmpty() && !startsBlock(lines, i)) {
                paragraph.add(lines[i].text)
                i += 1
            }
            add(MarkdownBlock.Content.Paragraph(paragraph.joinToString("\n")), start)
        }
        return blocks
    }

    private fun startsBlock(lines: List<MarkdownLine>, index: Int): Boolean {
        val line = lines[index].text
        return fence(line) != null || heading(line) != null || rule(line) || quote(line) != null ||
            marker(line)?.let { it.indent <= 3 } == true ||
            (index + 1 < lines.size && (setext(lines[index + 1].text) != null || tableHeader(line, lines[index + 1].text) != null)) ||
            line.trimmingWhitespaces().startsWith("$$") ||
            line.trimmingWhitespaces().startsWith("\\[")
    }

    private fun indentation(value: String): Int {
        var columns = 0
        for (character in value) {
            columns += when (character) {
                ' ' -> 1
                '\t' -> 4
                else -> return columns
            }
        }
        return columns
    }

    private fun removingIndent(value: String, columns: Int): String {
        var remaining = columns
        var index = 0
        while (index < value.length && remaining > 0 && (value[index] == ' ' || value[index] == '\t')) {
            remaining -= if (value[index] == '\t') 4 else 1
            index += 1
        }
        return value.substring(index)
    }

    private fun fence(value: String): Fence? {
        if (indentation(value) > 3) return null
        val trimmed = value.trimmingWhitespaces()
        val marker = trimmed.firstOrNull() ?: return null
        if (marker != '`' && marker != '~') return null
        val count = trimmed.takeWhile { it == marker }.length
        if (count < 3) return null
        val info = trimmed.substring(count).trimmingWhitespaces()
        if (marker == '`' && '`' in info) return null
        return Fence(marker = marker.code, count = count, language = firstWord(info).lowercase())
    }

    /** `info.split(whereSeparator: \.isWhitespace).first ?? ""`. */
    private fun firstWord(text: String): String {
        val scalars = text.scalars()
        val start = scalars.indexOfFirst { !isSwiftWhitespace(it) }
        if (start < 0) return ""
        var end = start
        while (end < scalars.size && !isSwiftWhitespace(scalars[end])) end += 1
        return scalars.text(start, end)
    }

    private fun closes(value: String, open: Fence): Boolean {
        val close = fence(value) ?: return false
        if (close.marker != open.marker || close.count < open.count) return false
        return value.trimmingWhitespaces().substring(close.count).trimmingWhitespaces().isEmpty()
    }

    private fun heading(value: String): Pair<Int, String>? {
        if (indentation(value) > 3) return null
        val text = value.trimmingWhitespaces()
        val count = text.takeWhile { it == '#' }.length
        if (count !in 1..6) return null
        if (text.length != count && !(count < text.length && isSwiftWhitespace(text.codePointAt(count)))) return null
        val content = text.substring(count).trimmingWhitespaces()
        return count to content.replace(closingHashes, "")
    }

    private fun setext(value: String): Int? {
        val text = value.trimmingWhitespaces()
        if (text.isEmpty() || indentation(value) > 3) return null
        if (text.all { it == '=' }) return 1
        if (text.all { it == '-' }) return 2
        return null
    }

    private fun rule(value: String): Boolean {
        if (indentation(value) > 3) return false
        val text = value.scalars().filter { !isSwiftWhitespace(it) }
        if (text.size < 3) return false
        val first = text.first()
        if (first != '-'.code && first != '*'.code && first != '_'.code) return false
        return text.all { it == first }
    }

    private fun quote(value: String): String? {
        if (indentation(value) > 3) return null
        val text = value.dropWhile { it == ' ' }
        if (text.firstOrNull() != '>') return null
        var rest = text.substring(1)
        if (rest.firstOrNull() == ' ') rest = rest.substring(1)
        return rest
    }

    private fun marker(value: String): Marker? {
        val indent = indentation(value)
        val chars = value.dropWhile { it == ' ' || it == '\t' }.scalars()
        if (chars.isEmpty()) return null
        var end = 0
        var number: Int? = null
        if (chars[0] == '-'.code || chars[0] == '*'.code || chars[0] == '+'.code) {
            end = 1
        } else {
            while (end < chars.size && chars[end] in '0'.code..'9'.code) end += 1
            if (end !in 1..9 || end >= chars.size || (chars[end] != '.'.code && chars[end] != ')'.code)) return null
            number = chars.text(0, end).toInt()
            end += 1
        }
        if (end != chars.size && !isSwiftWhitespace(chars[end])) return null
        val markerEnd = end
        while (end < chars.size && isSwiftWhitespace(chars[end])) end += 1
        val contentIndent = indent + markerEnd + maxOf(1, minOf(4, end - markerEnd))
        var body = chars.text(end)
        var checked: Boolean? = null
        if (body.startsWith("[ ] ") || body.startsWith("[x] ") || body.startsWith("[X] ")) {
            checked = !body.startsWith("[ ]")
            body = body.substring(4)
        }
        return Marker(indent = indent, contentIndent = contentIndent, number = number, body = body, checked = checked)
    }

    private fun containsTablePipe(value: String): Boolean =
        tableCells(value).size > 1 || value.trimmingWhitespaces().startsWith("|")

    private fun tableHeader(line: String, delimiter: String): Pair<List<String>, List<MarkdownTable.Alignment>>? {
        if (!containsTablePipe(line)) return null
        val headers = tableCells(line)
        val cells = tableCells(delimiter)
        if (headers.isEmpty() || headers.size != cells.size) return null
        val alignments = mutableListOf<MarkdownTable.Alignment>()
        for (cell in cells) {
            val trimmed = cell.trimmingWhitespaces()
            if (!delimiterCell.containsMatchIn(trimmed)) return null
            alignments.add(when {
                trimmed.startsWith(":") && trimmed.endsWith(":") -> MarkdownTable.Alignment.center
                trimmed.endsWith(":") -> MarkdownTable.Alignment.trailing
                else -> MarkdownTable.Alignment.leading
            })
        }
        return headers to alignments
    }

    private fun tableCells(value: String): List<String> {
        val chars = value.trimmingWhitespaces().scalars()
        val cells = mutableListOf<String>()
        val buffer = StringBuilder()
        var index = 0
        var ticks = 0
        while (index < chars.size) {
            if (chars[index] == '\\'.code && index + 1 < chars.size) {
                buffer.append(chars.text(index, index + 2))
                index += 2
                continue
            }
            if (chars[index] == '`'.code) {
                var end = index
                while (end < chars.size && chars[end] == '`'.code) end += 1
                val run = end - index
                if (ticks == 0) ticks = run else if (ticks == run) ticks = 0
                buffer.append(chars.text(index, end))
                index = end
                continue
            }
            if (chars[index] == '|'.code && ticks == 0) {
                cells.add(buffer.toString().trimmingWhitespaces())
                buffer.clear()
            } else {
                buffer.appendCodePoint(chars[index])
            }
            index += 1
        }
        cells.add(buffer.toString().trimmingWhitespaces())
        if (chars.firstOrNull() == '|'.code && cells.firstOrNull() == "") cells.removeAt(0)
        if (chars.lastOrNull() == '|'.code && cells.lastOrNull() == "") cells.removeAt(cells.size - 1)
        return cells
    }

    private companion object {
        val closingHashes = Regex("[ \\t]+#+[ \\t]*$")
        val delimiterCell = Regex("^:?-{3,}:?$")
    }
}
