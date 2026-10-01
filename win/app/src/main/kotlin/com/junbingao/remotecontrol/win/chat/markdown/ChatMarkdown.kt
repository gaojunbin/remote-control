package com.junbingao.remotecontrol.win.chat.markdown

import androidx.compose.ui.text.font.FontWeight
import kotlin.math.max

/**
 * A message as the chat draws it: the blocks the web's `.md` lays out, each with the margins its
 * rule gives it once they have collapsed the way the browser collapses them (`chat.css` §
 * markdown).
 */
sealed interface ChatMarkdown {
    /** The pipeline was not there: the text as it arrived (`.md-plain`). */
    data class Plain(val text: String) : ChatMarkdown

    data class Blocks(val stack: MDStack) : ChatMarkdown
}

/**
 * Blocks one after another. `gaps[i]` is the space above block `i`; the stack's first block
 * starts at the top and its last ends at the bottom, because the margins at either end belong to
 * the container around it. `top` and `bottom` are what the first block's top margin and the last
 * one's bottom margin come to after collapsing through the blocks that hold them.
 */
data class MDStack(
    val blocks: List<MDBlock> = emptyList(),
    val gaps: List<Float> = emptyList(),
    val top: Float = 0f,
    val bottom: Float = 0f,
)

data class MDBlock(
    val id: Int,
    val kind: Kind,
    /** This block's own margins, as its rule sets them. */
    val marginTop: Float,
    val marginBottom: Float,
    /** The margins of what it holds that collapse through its edges, where it has no padding or border to stop them. */
    val innerTop: Float = 0f,
    val innerBottom: Float = 0f,
) {
    /** The margins it meets its neighbours with. */
    val top: Float get() = max(marginTop, innerTop)
    val bottom: Float get() = max(marginBottom, innerBottom)

    sealed interface Kind {
        /** A paragraph, a heading, or the text of a tight list item. */
        data class Text(val text: MDText) : Kind
        data class List(val list: MDList) : Kind
        data class Quote(val stack: MDStack) : Kind

        /** A box with no edge of its own — the footnotes' `section`. */
        data class Group(val stack: MDStack) : Kind
        data class Code(val code: MDCode) : Kind
        data class Table(val table: MDTable) : Kind
        data object Rule : Kind
    }
}

/** A run of inline content and the type it is set in. */
data class MDText(val inlines: List<MDInline>, val size: Float, val weight: MDWeight, val tracking: Float = 0f)

/** CSS weights, so `<strong>` can be `bolder` than whatever it sits in. */
enum class MDWeight(val rawValue: Int) {
    regular(400),
    medium(500),
    semibold(600),
    bold(700),
    black(900);

    /** `font-weight: bolder`. */
    val bolder: MDWeight
        get() = when (this) {
            regular, medium -> bold
            semibold, bold, black -> black
        }

    val fontWeight: FontWeight get() = FontWeight(rawValue)
}

sealed interface MDInline {
    data class Text(val value: String, val marks: MDMarks) : MDInline

    /** `<code>` outside a `<pre>`: a tinted monospace chip. */
    data class Code(val value: String, val marks: MDMarks) : MDInline

    /** `<br>`. */
    data object LineBreak : MDInline

    /** A task list item's disabled checkbox. */
    data class Checkbox(val checked: Boolean) : MDInline

    /** `<img>`, drawn as its alternative text. */
    data class Image(val alt: String) : MDInline

    val isCode: Boolean get() = this is Code
    val isLineBreak: Boolean get() = this is LineBreak

    /** A chip or a link, which the decoration layer draws for. */
    val isDecorated: Boolean
        get() = when (this) {
            is Code -> true
            is Text -> marks.link != null
            else -> false
        }
}

data class MDMarks(
    val weight: MDWeight? = null,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
    val superscript: Boolean = false,
)

data class MDList(
    val ordered: Boolean,
    val start: Int,
    /** How many lists hold this one, which picks the bullet: disc, circle, then square. */
    val depth: Int,
    val items: List<MDStack>,
)

data class MDCode(
    /** rehype-highlight marked the block `hljs`: the white inner box and the theme's ink, highlighted or not. */
    val highlighted: Boolean,
    val lines: List<List<MDCodeRun>>,
    /** What Copy puts on the clipboard: the code element's text. */
    val source: String,
)

data class MDCodeRun(val text: String, val style: HighlightStyle)

data class MDTable(val header: List<MDCell>, val rows: List<List<MDCell>>, val columns: Int)

data class MDCell(val inlines: List<MDInline>, val align: Align, val header: Boolean) {
    enum class Align { leading, center, trailing }
}
