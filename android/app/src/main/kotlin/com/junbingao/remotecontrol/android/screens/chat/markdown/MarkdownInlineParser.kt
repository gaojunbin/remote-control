package com.junbingao.remotecontrol.android.screens.chat.markdown

/** One stretch of inline Markdown with the emphasis it carries. */
data class MarkdownRun(
    val text: String,
    val strong: Boolean = false,
    val em: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

/**
 * The inline half of Markdown as the iPhone reads it — `AttributedString(markdown:)` with
 * `.inlineOnlyPreservingWhitespace` — for the paragraphs, headings, list items and table cells the
 * block parser hands over: emphasis, strong emphasis, code spans, strikethrough, links and
 * autolinks, backslash escapes and entities, with every space and line break kept where it was.
 * Nothing here evaluates HTML or loads anything; an image is its description.
 *
 * Emphasis follows CommonMark's delimiter rules (left- and right-flanking runs, `_` not inside a
 * word, the rule of three), so `snake_case_names` and `2 * 3 * 4` stay as they were typed.
 */
object MarkdownInlineParser {
    fun runs(source: String): List<MarkdownRun> {
        val nodes = Scanner(source).scan()
        processEmphasis(nodes)
        val out = mutableListOf<MarkdownRun>()
        flatten(nodes, Style(), out)
        return merge(out)
    }

    // Nodes

    private sealed interface Node
    private class Literal(val text: String) : Node
    private class CodeSpan(val text: String) : Node
    private class Delimiter(val char: Char, var count: Int, val canOpen: Boolean, val canClose: Boolean, val original: Int) : Node
    private class Styled(val kind: Kind, val children: List<Node>) : Node
    private class Link(val destination: String, val children: List<Node>) : Node

    private enum class Kind { em, strong, strike }

    private data class Style(
        val strong: Boolean = false,
        val em: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null,
    )

    // Scanning: code spans, escapes, entities, links and autolinks first, then delimiter runs.

    private class Scanner(private val source: String) {
        private var index = 0
        private val nodes = mutableListOf<Node>()
        private val text = StringBuilder()

        fun scan(): MutableList<Node> {
            while (index < source.length) step()
            flushText()
            return nodes
        }

        private fun step() {
            val character = source[index]
            when {
                character == '\\' && index + 1 < source.length && source[index + 1] in ESCAPABLE -> {
                    text.append(source[index + 1])
                    index += 2
                }
                // A backslash at the end of a line is a hard break: the line break stays, the backslash goes.
                character == '\\' && index + 1 < source.length && source[index + 1] == '\n' -> {
                    text.append('\n')
                    index += 2
                }
                character == '`' -> codeSpan()
                character == '&' -> entity()
                character == '<' -> autolink()
                character == '!' && index + 1 < source.length && source[index + 1] == '[' -> {
                    if (!link(image = true)) {
                        text.append('!')
                        index += 1
                    }
                }
                character == '[' -> if (!link(image = false)) {
                    text.append('[')
                    index += 1
                }
                character == '*' || character == '_' || character == '~' -> delimiterRun(character)
                else -> {
                    text.append(character)
                    index += 1
                }
            }
        }

        private fun flushText() {
            if (text.isEmpty()) return
            nodes.add(Literal(text.toString()))
            text.clear()
        }

        private fun codeSpan() {
            val start = index
            var end = index
            while (end < source.length && source[end] == '`') end += 1
            val ticks = end - start
            var search = end
            while (search < source.length) {
                if (source[search] != '`') {
                    search += 1
                    continue
                }
                var close = search
                while (close < source.length && source[close] == '`') close += 1
                if (close - search == ticks) {
                    flushText()
                    var body = source.substring(end, search).replace('\n', ' ')
                    if (body.length >= 2 && body.startsWith(' ') && body.endsWith(' ') && body.isNotBlank()) {
                        body = body.substring(1, body.length - 1)
                    }
                    nodes.add(CodeSpan(body))
                    index = close
                    return
                }
                search = close
            }
            // No closing run of the same length: the backticks are what they look like.
            text.append(source, start, end)
            index = end
        }

        private fun entity() {
            val match = ENTITY.matchAt(source, index)
            val decoded = match?.let { decodeEntity(it.value) }
            if (match == null || decoded == null) {
                text.append('&')
                index += 1
                return
            }
            text.append(decoded)
            index += match.value.length
        }

        private fun autolink() {
            val match = AUTOLINK.matchAt(source, index) ?: EMAIL.matchAt(source, index)
            if (match == null) {
                text.append('<')
                index += 1
                return
            }
            val target = match.value.substring(1, match.value.length - 1)
            flushText()
            val destination = if (match.value.contains('@') && !target.contains(':')) "mailto:$target" else target
            nodes.add(Link(destination, listOf(Literal(target))))
            index += match.value.length
        }

        /** `[text](destination "title")`, or `![description](…)`. False when it is not one. */
        private fun link(image: Boolean): Boolean {
            val open = if (image) index + 1 else index
            val close = matchingBracket(open) ?: return false
            if (close + 1 >= source.length || source[close + 1] != '(') return false
            val destinationEnd = destinationEnd(close + 2) ?: return false
            val inside = source.substring(close + 2, destinationEnd).trim()
            val destination = inside.substringBefore(' ').removeSurrounding("<", ">")
            val label = source.substring(open + 1, close)
            flushText()
            val children = Scanner(label).scan().also { processEmphasis(it) }
            // An image is drawn as its description and opens nothing.
            nodes.add(Link(if (image) "" else destination, children))
            index = destinationEnd + 1
            return true
        }

        private fun matchingBracket(open: Int): Int? {
            var depth = 0
            var position = open
            while (position < source.length) {
                when (source[position]) {
                    '\\' -> position += 1
                    '`' -> {
                        // A code span inside a link's text holds its brackets as text.
                        var end = position
                        while (end < source.length && source[end] == '`') end += 1
                        val ticks = source.substring(position, end)
                        val closing = source.indexOf(ticks, end)
                        position = if (closing >= 0) closing + ticks.length - 1 else end - 1
                    }
                    '[' -> depth += 1
                    ']' -> {
                        depth -= 1
                        if (depth == 0) return position
                    }
                }
                position += 1
            }
            return null
        }

        private fun destinationEnd(from: Int): Int? {
            var depth = 0
            var position = from
            while (position < source.length) {
                when (source[position]) {
                    '\\' -> position += 1
                    '\n' -> return null
                    '(' -> depth += 1
                    ')' -> {
                        if (depth == 0) return position
                        depth -= 1
                    }
                }
                position += 1
            }
            return null
        }

        private fun delimiterRun(character: Char) {
            val start = index
            var end = index
            while (end < source.length && source[end] == character) end += 1
            val count = end - start
            val before = if (start == 0) ' ' else source[start - 1]
            val after = if (end >= source.length) ' ' else source[end]
            val leftFlanking = !after.isWhitespace() && (!after.isPunctuation() || before.isWhitespace() || before.isPunctuation())
            val rightFlanking = !before.isWhitespace() && (!before.isPunctuation() || after.isWhitespace() || after.isPunctuation())
            val canOpen: Boolean
            val canClose: Boolean
            if (character == '_') {
                canOpen = leftFlanking && (!rightFlanking || before.isPunctuation())
                canClose = rightFlanking && (!leftFlanking || after.isPunctuation())
            } else {
                canOpen = leftFlanking
                canClose = rightFlanking
            }
            // A run of tildes other than one or two strikes nothing through.
            if (character == '~' && count > 2) {
                text.append(source, start, end)
                index = end
                return
            }
            flushText()
            nodes.add(Delimiter(character, count, canOpen, canClose, count))
            index = end
        }
    }

    /** CommonMark's "process emphasis", over one level of nodes. */
    private fun processEmphasis(nodes: MutableList<Node>) {
        var position = 0
        while (position < nodes.size) {
            val closer = nodes[position] as? Delimiter
            if (closer == null || !closer.canClose || closer.count == 0) {
                position += 1
                continue
            }
            val found = (position - 1 downTo 0).firstOrNull { at ->
                val opener = nodes[at] as? Delimiter
                opener != null && opener.char == closer.char && opener.canOpen && opener.count > 0 && compatible(opener, closer)
            }
            if (found == null) {
                position += 1
                continue
            }
            val opener = nodes[found] as Delimiter
            val use = when {
                closer.char == '~' -> closer.count
                closer.count >= 2 && opener.count >= 2 -> 2
                else -> 1
            }
            val kind = when {
                closer.char == '~' -> Kind.strike
                use == 2 -> Kind.strong
                else -> Kind.em
            }
            val inner = nodes.subList(found + 1, position).toList()
            repeat(position - found - 1) { nodes.removeAt(found + 1) }
            nodes.add(found + 1, Styled(kind, inner))
            opener.count -= use
            closer.count -= use
            position = found + 2
            if (opener.count == 0) {
                nodes.removeAt(found)
                position -= 1
            }
            if (closer.count == 0) nodes.removeAt(position)
        }
    }

    /** The rule of three, and strikethrough's own: a tilde run only closes one of its own length. */
    private fun compatible(opener: Delimiter, closer: Delimiter): Boolean {
        if (closer.char == '~') return opener.count == closer.count
        val either = (opener.canOpen && opener.canClose) || (closer.canOpen && closer.canClose)
        if (!either) return true
        val sum = opener.original + closer.original
        return sum % 3 != 0 || (opener.original % 3 == 0 && closer.original % 3 == 0)
    }

    private fun flatten(nodes: List<Node>, style: Style, out: MutableList<MarkdownRun>) {
        for (node in nodes) {
            when (node) {
                is Literal -> out.add(run(node.text, style, code = false))
                is CodeSpan -> out.add(run(node.text, style, code = true))
                // What did not pair up is what was typed.
                is Delimiter -> if (node.count > 0) out.add(run(node.char.toString().repeat(node.count), style, code = false))
                is Styled -> flatten(
                    node.children,
                    when (node.kind) {
                        Kind.em -> style.copy(em = true)
                        Kind.strong -> style.copy(strong = true)
                        Kind.strike -> style.copy(strike = true)
                    },
                    out,
                )
                is Link -> flatten(node.children, if (node.destination.isEmpty()) style else style.copy(link = node.destination), out)
            }
        }
    }

    private fun run(text: String, style: Style, code: Boolean) =
        MarkdownRun(text, strong = style.strong, em = style.em, code = code, strike = style.strike, link = style.link)

    private fun merge(runs: List<MarkdownRun>): List<MarkdownRun> {
        val merged = mutableListOf<MarkdownRun>()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = "") == run.copy(text = "")) {
                merged[merged.size - 1] = last.copy(text = last.text + run.text)
            } else {
                merged.add(run)
            }
        }
        return merged
    }

    private const val ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});")
    private val AUTOLINK = Regex("<[A-Za-z][A-Za-z0-9+.\\-]{1,31}:[^<>\\s]*>")
    private val EMAIL = Regex("<[A-Za-z0-9.!#$%&'*+/=?^_`{|}~\\-]+@[A-Za-z0-9](?:[A-Za-z0-9\\-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9\\-]{0,61}[A-Za-z0-9])?)*>")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "middot" to "·", "times" to "×",
        "rarr" to "→", "larr" to "←", "deg" to "°",
    )

    private fun decodeEntity(entity: String): String? {
        val body = entity.substring(1, entity.length - 1)
        if (body.startsWith("#")) {
            val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) body.substring(2).toIntOrNull(16) else body.substring(1).toIntOrNull()
            if (code == null || code == 0 || code > 0x10FFFF) return "�"
            return String(Character.toChars(code))
        }
        return NAMED[body]
    }

    private fun Char.isPunctuation(): Boolean =
        this in ESCAPABLE || Character.getType(this).let {
            it == Character.CONNECTOR_PUNCTUATION.toInt() || it == Character.DASH_PUNCTUATION.toInt() ||
                it == Character.START_PUNCTUATION.toInt() || it == Character.END_PUNCTUATION.toInt() ||
                it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || it == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                it == Character.OTHER_PUNCTUATION.toInt()
        }
}
