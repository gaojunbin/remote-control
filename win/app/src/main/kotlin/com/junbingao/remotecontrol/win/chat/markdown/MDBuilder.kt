package com.junbingao.remotecontrol.win.chat.markdown

import kotlin.math.max

/**
 * Turns the web's hast into the chat's blocks with the margins `chat.css` gives them: a paragraph
 * 12 below, a heading 20 above and 8 below, a list, a quote, a code block and a table 12 below, a
 * rule 16 either side, a list item 4 below — collapsed as the browser collapses them, through
 * every box that has no padding or border of its own to stop them.
 */
object MDBuilder {
    fun document(nodes: List<HastNode>): ChatMarkdown {
        val stack = Builder().stack(nodes, depth = 0)
        // `.md > *:first-child` and `:last-child` zero their own margins, and `.md` holds what
        // still collapses through them: it is a flex item, a formatting context of its own, so
        // nothing leaves it.
        return ChatMarkdown.Blocks(
            stack.copy(top = stack.blocks.firstOrNull()?.innerTop ?: 0f, bottom = stack.blocks.lastOrNull()?.innerBottom ?: 0f),
        )
    }

    const val body = 14f
}

private class Builder {
    private var nextID = 0

    /**
     * The blocks a container holds, with the inline content between them gathered into anonymous
     * paragraphs of no margin, as CSS wraps it.
     */
    fun stack(nodes: List<HastNode>, depth: Int): MDStack {
        val blocks = mutableListOf<MDBlock>()
        val pending = mutableListOf<HastNode>()
        fun flush() {
            if (pending.none { it.isInlineContent }) {
                pending.clear()
                return
            }
            val inlines = MDInlines.build(pending, MDMarks(), base = MDWeight.regular)
            pending.clear()
            if (inlines.isEmpty()) return
            blocks += block(MDBlock.Kind.Text(MDText(inlines, MDBuilder.body, MDWeight.regular)), top = 0f, bottom = 0f)
        }
        for (node in nodes) {
            if (node !is HastNode.Element) {
                pending += node
                continue
            }
            when (val made = blockElement(node.element, depth)) {
                Made.Inline -> pending += node
                Made.Hidden -> flush()
                is Made.Block -> {
                    flush()
                    blocks += made.block
                }
            }
        }
        flush()
        return collapse(blocks)
    }

    private sealed interface Made {
        /** Inline content: part of the anonymous paragraph around it. */
        data object Inline : Made

        /** A block that takes no room, such as the footnotes' screen-reader heading, positioned out of the flow. */
        data object Hidden : Made

        data class Block(val block: MDBlock) : Made
    }

    private fun blockElement(element: HastElement, depth: Int): Made = when (element.tag) {
        "p" -> Made.Block(textBlock(element, MDBuilder.body, MDWeight.regular, top = 0f, bottom = 12f))
        "h1" -> Made.Block(textBlock(element, 17f, MDWeight.semibold, tracking = -0.01f, top = 20f, bottom = 8f))
        "h2", "h3", "h4" ->
            if ("sr-only" in element.properties.className) {
                Made.Hidden
            } else {
                Made.Block(textBlock(element, 15f, MDWeight.semibold, tracking = -0.01f, top = 20f, bottom = 8f))
            }
        "h5" -> {
            // No `.md` rule and no reset: the browser's own 0.83em, bold, 1.67em either side.
            val size = MDBuilder.body * 0.83f
            Made.Block(textBlock(element, size, MDWeight.bold, top = size * 1.67f, bottom = size * 1.67f))
        }
        "h6" -> {
            val size = MDBuilder.body * 0.67f
            Made.Block(textBlock(element, size, MDWeight.bold, top = size * 2.33f, bottom = size * 2.33f))
        }
        "ul", "ol" -> Made.Block(list(element, depth))
        "blockquote" -> {
            val inner = stack(element.children, depth)
            Made.Block(block(MDBlock.Kind.Quote(inner), top = 0f, bottom = 12f, innerTop = inner.top, innerBottom = inner.bottom))
        }
        "pre" -> Made.Block(block(MDBlock.Kind.Code(MDCodeBuilder.code(element)), top = 0f, bottom = 12f))
        "table" -> Made.Block(block(MDBlock.Kind.Table(MDTableBuilder.table(element)), top = 0f, bottom = 12f))
        "hr" -> Made.Block(block(MDBlock.Kind.Rule, top = 16f, bottom = 16f))
        "section", "div" -> {
            val inner = stack(element.children, depth)
            Made.Block(block(MDBlock.Kind.Group(inner), top = 0f, bottom = 0f, innerTop = inner.top, innerBottom = inner.bottom))
        }
        else -> Made.Inline
    }

    private fun textBlock(element: HastElement, size: Float, weight: MDWeight, tracking: Float = 0f, top: Float, bottom: Float): MDBlock {
        val inlines = MDInlines.build(element.children, MDMarks(), base = weight)
        return block(MDBlock.Kind.Text(MDText(inlines, size, weight, tracking)), top = top, bottom = bottom)
    }

    /** `ul` and `ol`: 12 below, 20 of gutter for the markers, each item 4 below. */
    private fun list(element: HastElement, depth: Int): MDBlock {
        val items = element.elements.filter { it.tag == "li" }.map { item ->
            val content = stack(item.children, depth + 1)
            // The item's own margins, with what collapses through it.
            content.copy(top = max(0f, content.top), bottom = max(4f, content.bottom))
        }
        val list = MDList(ordered = element.tag == "ol", start = element.properties.start ?: 1, depth = depth, items = items)
        return block(
            MDBlock.Kind.List(list),
            top = 0f,
            bottom = 12f,
            innerTop = items.firstOrNull()?.top ?: 0f,
            innerBottom = items.lastOrNull()?.bottom ?: 0f,
        )
    }

    private fun block(kind: MDBlock.Kind, top: Float, bottom: Float, innerTop: Float = 0f, innerBottom: Float = 0f): MDBlock {
        nextID += 1
        return MDBlock(id = nextID, kind = kind, marginTop = top, marginBottom = bottom, innerTop = innerTop, innerBottom = innerBottom)
    }

    companion object {
        /** Adjoining margins collapse to the larger of the two; the first block's top and the last one's bottom are the container's to collapse further. */
        fun collapse(blocks: List<MDBlock>): MDStack = MDStack(
            blocks = blocks,
            gaps = blocks.indices.map { index -> if (index == 0) 0f else max(blocks[index - 1].bottom, blocks[index].top) },
            top = blocks.firstOrNull()?.top ?: 0f,
            bottom = blocks.lastOrNull()?.bottom ?: 0f,
        )
    }
}

/** Whether this node is inline content that makes a line box: anything but the whitespace the pipeline puts between blocks. */
val HastNode.isInlineContent: Boolean
    get() = when (this) {
        is HastNode.Text -> !value.all { it.isWhitespace() }
        is HastNode.Element -> true
    }
