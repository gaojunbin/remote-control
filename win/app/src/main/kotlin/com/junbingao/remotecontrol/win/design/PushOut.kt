package com.junbingao.remotecontrol.win.design

import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/**
 * The Mac's text system will not end a two-line paragraph on one short word: it pushes the word
 * before it down to keep it company — "Add the machine where your coding agents / are installed."
 * where Compose breaks after "are" and leaves "installed." alone. That is the `pushOut` of Apple's
 * standard line-break strategy, which SwiftUI's `Text` follows, so every wrapped text on the Mac has it.
 *
 * Its rule, read off SwiftUI over a thousand paragraphs: only a paragraph of exactly two lines,
 * only when the second is a single word of at most ten characters (a Chinese character is a word
 * there), and only when the word moved down leaves the first line at least three fifths as long as
 * the new second one — no first line of two short words over a long second. The word moved is
 * the first line's last, and it is glued to the one it joins (`Glue`), so Compose's own line
 * breaking does the rest.
 */
internal object PushOut {
    private const val LONGEST_ORPHAN = 10
    private const val LONGEST_STRETCH = 5f / 3f

    /** `text` as it shows wrapped at `maxWidth`: with the joins its two-line paragraphs need. */
    fun at(maxWidth: Int, text: AnnotatedString, style: ComposeTextStyle, maxLines: Int, overflow: TextOverflow, measurer: TextMeasurer): AnnotatedString {
        val laid = measurer.measure(text, style, overflow, maxLines = maxLines, constraints = Constraints(maxWidth = maxWidth))
        if (laid.lineCount < 2) return text
        return apply(text, laid, maxWidth) { piece ->
            measurer.measure(piece, style, softWrap = false, maxLines = 1).multiParagraph.intrinsics.maxIntrinsicWidth
        }
    }

    /**
     * `text`, laid out as `layout` at `maxWidth`, with the joins its two-line paragraphs need;
     * `widthOf` measures a piece of it set on one line.
     */
    fun apply(text: AnnotatedString, layout: TextLayoutResult, maxWidth: Int, widthOf: (AnnotatedString) -> Float): AnnotatedString {
        val joins = mutableListOf<Glue.Join>()
        var start = 0
        while (start < text.length) {
            val end = text.text.indexOf('\n', start).let { if (it < 0) text.length else it }
            join(text, layout, start, end, maxWidth, widthOf)?.let(joins::add)
            start = end + 1
        }
        return if (joins.isEmpty()) text else Glue.apply(text, joins)
    }

    /** The join one paragraph, `start until end`, needs, if any. */
    private fun join(
        text: AnnotatedString,
        layout: TextLayoutResult,
        start: Int,
        end: Int,
        maxWidth: Int,
        widthOf: (AnnotatedString) -> Float,
    ): Glue.Join? {
        val words = text.text.trimEnd(start, end)
        if (words <= start) return null
        val first = layout.getLineForOffset(start)
        val last = layout.getLineForOffset(words - 1)
        // Two lines, both laid out: a paragraph a line limit cut short is not one to rebalance.
        if (last != first + 1 || layout.getLineEnd(last, visibleEnd = true) < words) return null
        val second = layout.getLineStart(last)
        val breaks = BreakIterator.getLineInstance(Locale.ENGLISH).apply { setText(text.text.substring(start, end)) }
        // A break the text allows, not a word too long for the line cut in two.
        if (second <= start || !breaks.isBoundary(second - start)) return null
        if (breaks.following(second - start) + start < words) return null
        if (text.text.codePointCount(second, words) > LONGEST_ORPHAN) return null
        val moved = breaks.preceding(second - start) + start
        if (moved <= start) return null
        val shortened = abs(layout.getHorizontalPosition(moved, true) - layout.getHorizontalPosition(start, true))
        val joined = widthOf(text.subSequence(moved, words))
        if (joined > maxWidth || joined > LONGEST_STRETCH * shortened) return null
        return Glue.Join(gapStart = text.text.trimEnd(moved, second), wordStart = second)
    }

    /** Where the run of whitespace that ends `start until end` begins. */
    private fun String.trimEnd(start: Int, end: Int): Int {
        var index = end
        while (index > start && this[index - 1].isWhitespace()) index--
        return index
    }
}

/**
 * Shows `text` with the joins `PushOut` finds at the width the text is laid out at, in `shown`.
 * The width is known only once the text is measured, so the joined text follows a frame later.
 */
internal fun Modifier.pushingOut(
    text: AnnotatedString,
    style: ComposeTextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    measurer: TextMeasurer,
    shown: MutableState<AnnotatedString>,
): Modifier = layout { measurable, constraints ->
    if (constraints.hasBoundedWidth) {
        val pushed = PushOut.at(constraints.maxWidth, text, style, maxLines, overflow, measurer)
        if (pushed != shown.value) shown.value = pushed
    }
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
