package com.junbingao.remotecontrol.win.chat.markdown

/**
 * A fenced block as the web draws it: `<pre><code>`, highlighted by rehype-highlight when its
 * language is one of highlight.js's common ones. The theme's colours are worked out span by span
 * here, once.
 */
object MDCodeBuilder {
    fun code(pre: HastElement): MDCode {
        val code = pre.elements.firstOrNull { it.tag == "code" }
        val classes = code?.properties?.className ?: emptyList()
        val highlighted = "hljs" in classes
        // Unhighlighted code keeps the ink of the page; `.hljs` sets its own.
        val base = if (highlighted) HighlightTheme.base else HighlightStyle(color = 0x111111)
        val runs = mutableListOf<MDCodeRun>()
        walk(code?.children ?: pre.children, base, ancestors = emptyList(), runs = runs)
        return MDCode(highlighted = highlighted, lines = lines(runs), source = code?.textContent ?: pre.textContent)
    }

    private fun walk(nodes: List<HastNode>, style: HighlightStyle, ancestors: List<List<String>>, runs: MutableList<MDCodeRun>) {
        for (node in nodes) {
            when (node) {
                is HastNode.Text -> runs += MDCodeRun(node.value, style)
                is HastNode.Element -> {
                    val classes = node.element.properties.className
                    val own = HighlightTheme.style(classes, ancestors, inherited = style)
                    walk(node.element.children, own, ancestors + listOf(classes), runs)
                }
            }
        }
    }

    /** The runs cut at every line feed. The line feed that ends the block ends its last line and starts no other, as in a `<pre>`. */
    fun lines(runs: List<MDCodeRun>): List<List<MDCodeRun>> {
        val lines = mutableListOf(mutableListOf<MDCodeRun>())
        for (run in runs) {
            for ((index, piece) in run.text.split("\n").withIndex()) {
                if (index > 0) lines += mutableListOf<MDCodeRun>()
                if (piece.isNotEmpty()) lines.last() += MDCodeRun(piece, run.style)
            }
        }
        if (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return lines
    }
}

/** A GFM table: its header row, its body rows and the alignment each cell takes from its column (`align`, which React writes as `text-align`). */
object MDTableBuilder {
    fun table(table: HastElement): MDTable {
        var header = emptyList<MDCell>()
        val rows = mutableListOf<List<MDCell>>()
        for (section in table.elements) {
            for (row in section.elements.filter { it.tag == "tr" }) {
                val cells = row.elements.filter { it.tag == "th" || it.tag == "td" }.map(::cell)
                if (section.tag == "thead") header = cells else rows += cells
            }
        }
        val columns = (listOf(header.size) + rows.map { it.size }).maxOrNull() ?: 0
        return MDTable(header = header, rows = rows, columns = columns)
    }

    private fun cell(element: HastElement): MDCell {
        val header = element.tag == "th"
        val align = when (element.properties.align) {
            "center" -> MDCell.Align.center
            "right" -> MDCell.Align.trailing
            else -> MDCell.Align.leading
        }
        val inlines = MDInlines.build(element.children, MDMarks(), base = if (header) MDWeight.medium else MDWeight.regular)
        return MDCell(inlines = inlines, align = align, header = header)
    }
}
