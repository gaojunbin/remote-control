package com.junbingao.remotecontrol.win.chat.markdown

/**
 * Inline content, as the browser lays it out under `white-space: normal`: line feeds and tabs are
 * spaces, a run of spaces is one — across the edges of `<strong>`, `<a>` and the rest — and no line
 * starts or ends with one.
 */
object MDInlines {
    fun build(nodes: List<HastNode>, marks: MDMarks, base: MDWeight): List<MDInline> {
        val raw = mutableListOf<MDInline>()
        collect(nodes, marks, base, raw)
        return collapse(raw)
    }

    private fun collect(nodes: List<HastNode>, marks: MDMarks, base: MDWeight, out: MutableList<MDInline>) {
        for (node in nodes) {
            when (node) {
                is HastNode.Text -> out += MDInline.Text(node.value, marks)
                is HastNode.Element -> collect(node.element, marks, base, out)
            }
        }
    }

    private fun collect(element: HastElement, marks: MDMarks, base: MDWeight, out: MutableList<MDInline>) {
        val inner = when (element.tag) {
            "strong", "b" -> marks.copy(weight = (marks.weight ?: base).bolder)
            "em", "i" -> marks.copy(italic = true)
            "del", "s" -> marks.copy(strike = true)
            "a" -> marks.copy(link = element.properties.href ?: "")
            "sup" -> marks.copy(superscript = true)
            "code" -> {
                out += MDInline.Code(element.textContent, marks)
                return
            }
            "br" -> {
                out += MDInline.LineBreak
                return
            }
            "input" -> {
                out += MDInline.Checkbox(checked = element.properties.checked ?: false)
                return
            }
            "img" -> {
                out += MDInline.Image(alt = element.properties.alt ?: "")
                return
            }
            else -> marks
        }
        collect(element.children, inner, base, out)
    }

    /** One space for every run of white space, none at the start or end of a line, and adjoining runs with the same marks joined. */
    fun collapse(inlines: List<MDInline>): List<MDInline> {
        val out = mutableListOf<MDInline>()
        // Whether the last thing laid out ends in a space, or is the start of a line, where a
        // space collapses away.
        var atSpace = true
        for (inline in inlines) {
            when (inline) {
                is MDInline.Text -> {
                    val text = squeeze(inline.value, dropLeading = atSpace)
                    if (text.isEmpty()) continue
                    atSpace = text.endsWith(" ")
                    append(MDInline.Text(text, inline.marks), out)
                }
                is MDInline.Code -> {
                    val text = squeeze(inline.value, dropLeading = false)
                    if (text.isEmpty()) continue
                    atSpace = false
                    out += MDInline.Code(text, inline.marks)
                }
                MDInline.LineBreak -> {
                    trimTrailingSpace(out)
                    out += MDInline.LineBreak
                    atSpace = true
                }
                is MDInline.Checkbox, is MDInline.Image -> {
                    out += inline
                    atSpace = false
                }
            }
        }
        trimTrailingSpace(out)
        return out
    }

    private fun squeeze(value: String, dropLeading: Boolean): String {
        val result = StringBuilder()
        var lastWasSpace = dropLeading
        for (character in value) {
            if (character == ' ' || character == '\n' || character == '\t' || character == '\r') {
                if (!lastWasSpace) result.append(' ')
                lastWasSpace = true
            } else {
                result.append(character)
                lastWasSpace = false
            }
        }
        return result.toString()
    }

    private fun append(inline: MDInline.Text, out: MutableList<MDInline>) {
        val previous = out.lastOrNull()
        if (previous is MDInline.Text && previous.marks == inline.marks) {
            out[out.size - 1] = MDInline.Text(previous.value + inline.value, inline.marks)
        } else {
            out += inline
        }
    }

    private fun trimTrailingSpace(out: MutableList<MDInline>) {
        val last = out.lastOrNull() as? MDInline.Text ?: return
        if (!last.value.endsWith(" ")) return
        val trimmed = last.value.dropLast(1)
        if (trimmed.isEmpty()) out.removeAt(out.size - 1) else out[out.size - 1] = MDInline.Text(trimmed, last.marks)
    }
}
