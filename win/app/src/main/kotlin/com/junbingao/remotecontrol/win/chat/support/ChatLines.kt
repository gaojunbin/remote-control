package com.junbingao.remotecontrol.win.chat.support

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import com.junbingao.remotecontrol.win.design.ExactHeight
import com.junbingao.remotecontrol.win.design.PushOut
import com.junbingao.remotecontrol.win.design.StackFrame
import com.junbingao.remotecontrol.win.design.composeTextStyle
import com.junbingao.remotecontrol.win.design.exactHeight
import com.junbingao.remotecontrol.win.design.roundHalfUp
import com.junbingao.remotecontrol.win.design.stackFrame
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/**
 * A text's lines, one `ChatLine` each, as SwiftUI sets a text of several lines: its line box on a
 * whole point, as the browser puts the first line of one, and every line after it a line box
 * further down — 38.4 pixels for a 12-point line of 1.6, where Skia would make it 39 — each drawn
 * on the pixel its exact place rounds to. Every line keeps what ends it, its spaces or its line
 * feed, so a selection across the lines copies the text as it was written.
 */
@Composable
internal fun ChatLines(lines: List<AnnotatedString>, line: ChatLineStyle, exact: ExactHeight) {
    val frame = remember { StackFrame(vertical = true) }
    val holders = remember(lines.size) { List(lines.size) { ExactHeight() } }
    Layout(
        content = { for ((index, text) in lines.withIndex()) ChatLine(text, line, holders[index], snapToPoint = false) },
        modifier = Modifier.stackFrame(frame),
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose) }
        var used = 0f
        for ((index, placeable) in placeables.withIndex()) used += placeable.height + holders[index].fraction
        val height = constraints.constrainHeight(used.roundToInt())
        exact.fraction = if (height == used.roundToInt()) used - height else 0f
        val width = max(constraints.minWidth, placeables.maxOfOrNull { it.width } ?: 0)
        layout(width, height) {
            // Where each line goes is told before any is placed: the line asks. The box's own
            // exact place, put on a whole point, is where the first line is drawn.
            val node = coordinates
            val base = frame.base(node)
            val snap = if (node == null) 0f else {
                val exactTop = frame.lineTop(node) + base
                roundHalfUp(exactTop / density) * density - exactTop
            }
            var top = base + snap
            val spans = placeables.mapIndexed { index, placeable ->
                val y = top.roundToInt()
                StackFrame.Span(y, y + placeable.height, top - y).also { top += placeable.height + holders[index].fraction }
            }
            frame.placed(node, spans)
            for ((index, placeable) in placeables.withIndex()) placeable.place(0, spans[index].start)
        }
    }
}

internal object ChatLines {
    /** A text cut at its line feeds, each line keeping the line feed that ends it. */
    fun atLineFeeds(text: AnnotatedString): List<AnnotatedString> {
        val lines = mutableListOf<AnnotatedString>()
        var start = 0
        while (true) {
            val feed = text.text.indexOf('\n', start)
            if (feed < 0) {
                lines += text.subSequence(start, text.length)
                return lines
            }
            lines += text.subSequence(start, feed + 1)
            start = feed + 1
        }
    }

    /** A wrapping text's lines at `width`, as Compose breaks it, after the Mac's two-line rule (`PushOut`) has joined what it joins. */
    fun wrapped(text: AnnotatedString, style: ComposeTextStyle, width: Int, measurer: TextMeasurer, placeholders: List<AnnotatedString.Range<Placeholder>>): List<AnnotatedString> {
        if (text.isEmpty()) return listOf(text)
        val joined = PushOut.at(width, text, style, Int.MAX_VALUE, TextOverflow.Clip, measurer)
        val layout = measurer.measure(joined, style, constraints = Constraints(maxWidth = width), placeholders = placeholders(joined, placeholders, text))
        if (layout.lineCount <= 1) return listOf(joined)
        return (0 until layout.lineCount).map { line ->
            val start = layout.getLineStart(line)
            val end = if (line == layout.lineCount - 1) joined.length else layout.getLineStart(line + 1)
            joined.subSequence(start, max(start, end))
        }
    }

    /** The inline images' places in `joined`, which the two-line rule may have moved by a joiner or two from where they were in `text`. */
    private fun placeholders(
        joined: AnnotatedString,
        placeholders: List<AnnotatedString.Range<Placeholder>>,
        text: AnnotatedString,
    ): List<AnnotatedString.Range<Placeholder>> {
        if (placeholders.isEmpty() || joined === text) return placeholders
        val sizes = placeholders.associate { it.start to it.item }
        val original = text.getStringAnnotations(INLINE_CONTENT, 0, text.length)
        val moved = joined.getStringAnnotations(INLINE_CONTENT, 0, joined.length)
        return original.zip(moved).mapNotNull { (was, now) -> sizes[was.start]?.let { AnnotatedString.Range(it, now.start, now.end) } }
    }

    /** The tag `appendInlineContent` files an inline image under. */
    const val INLINE_CONTENT = "androidx.compose.foundation.text.inlineContent"
}

/**
 * A wrapping text, cut into its lines at the width it is offered and drawn as `ChatLines`; a text
 * that fits on one line is one `ChatLine`. It answers intrinsic measurements itself, from the text,
 * as a `BasicText` would: the lines are composed only once the width is known.
 */
@Composable
internal fun ChatWrappedLines(text: AnnotatedString, line: ChatLineStyle, exact: ExactHeight) {
    val measurer = rememberTextMeasurer()
    val style = line.style
    val language = InterfaceLanguageSource.current
    val measuring = composeTextStyle(style.size, style.weight, style.mono, style.tracking * style.size, style.lineBox, line.color, line.textAlign, language)
    val placeholders = remember(text, line.inlineContent) {
        text.getStringAnnotations(ChatLines.INLINE_CONTENT, 0, text.length).mapNotNull { annotation ->
            line.inlineContent[annotation.item]?.let { AnnotatedString.Range(it.placeholder, annotation.start, annotation.end) }
        }
    }
    val policy = remember(text, measuring, placeholders) { IntrinsicTextPolicy(text, measuring, measurer, placeholders, style.lineBox) }
    Layout(
        content = {
            BoxWithConstraints {
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
                val lines = remember(text, width, measuring, placeholders) { ChatLines.wrapped(text, measuring, width, measurer, placeholders) }
                if (lines.size == 1) ChatLine(lines[0], line, exact) else ChatLines(lines, line, exact)
            }
        },
        measurePolicy = policy,
    )
}

/**
 * Measures its one child as it is, and answers the intrinsic measurements a `SubcomposeLayout`
 * cannot from the text itself: its widths on one line and at its narrowest, and its height at a
 * width — the lines it breaks into, each a line box tall.
 */
private class IntrinsicTextPolicy(
    private val text: AnnotatedString,
    private val style: ComposeTextStyle,
    private val measurer: TextMeasurer,
    private val placeholders: List<AnnotatedString.Range<Placeholder>>,
    private val lineBox: Float,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val placeable = measurables.firstOrNull()?.measure(constraints) ?: return layout(0, 0) {}
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    private fun layout(width: Int = Constraints.Infinity): TextLayoutResult =
        measurer.measure(text, style, constraints = Constraints(maxWidth = width), placeholders = placeholders)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        ceil(layout().multiParagraph.intrinsics.minIntrinsicWidth).toInt()

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        ceil(layout().multiParagraph.intrinsics.maxIntrinsicWidth).toInt()

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        (layout(width).lineCount * lineBox * density).roundToInt()

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        minIntrinsicHeight(measurables, width)
}

/** A text's own box, which reports the text's exact height (`exact`) to the stack around it. */
@Composable
internal fun ChatPass(exact: ExactHeight, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.exactHeight(exact)) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(constraints) ?: return@Layout layout(0, 0) {}
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
