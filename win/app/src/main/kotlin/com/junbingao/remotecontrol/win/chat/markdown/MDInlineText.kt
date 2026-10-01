package com.junbingao.remotecontrol.win.chat.markdown

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.junbingao.remotecontrol.win.chat.support.ChatKerning
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.SystemFace
import com.junbingao.remotecontrol.win.design.roundHalfUp
import java.net.URI
import kotlin.math.max
import kotlin.math.min

/**
 * One paragraph's inline content as one text, so it wraps, selects and hit-tests as one block of
 * text does in the browser.
 *
 * Inline code is `.md :not(pre) > code` — the monospaced face at 0.92em on a 5-point-radius tint,
 * 5 points of padding either side and 1 above and below. The padding is room in the line, made
 * with kerning (`ChatKerning`) and, at the start of a line, an inline spacer; the tint is drawn
 * behind the glyphs from where the layout put them (`draw`), and so is a link's underline, 2
 * points below the baseline in the text's own ink.
 */
class MDInlineText private constructor(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
) {
    /** A chip or a link is there, which the decoration layer draws for. */
    val isDecorated: Boolean
        get() = text.getStringAnnotations(CHIP, 0, text.length).isNotEmpty() || text.getStringAnnotations(LINK, 0, text.length).isNotEmpty()

    /**
     * The tint behind every code chip and the underline under every link in one line of the text,
     * from the marks the line's own text carries; the glyphs are the text's.
     */
    fun draw(scope: DrawScope, layout: TextLayoutResult, ink: Color) = with(scope) {
        val unit = density
        val line = layout.layoutInput.text
        for (chip in line.getStringAnnotations(CHIP, 0, line.length)) {
            val (size, weight) = chip.item.split(":").let { it[0].toFloat() to FontWeight(it[1].toInt()) }
            val (ascent, descent) = SystemFace.face(size, weight, mono = true).metrics(size)
            for ((row, left, right) in segments(layout, chip.start until chip.end)) {
                val baseline = layout.getLineBaseline(row)
                val top = baseline - (roundHalfUp(ascent) + 1) * unit
                val bottom = baseline + (roundHalfUp(descent) + 1) * unit
                val x = left - codePadding * unit
                drawRoundRect(codeTint, Offset(x, top), Size(right - x, bottom - top), CornerRadius(5 * unit, 5 * unit))
            }
        }
        for (link in line.getStringAnnotations(LINK, 0, line.length)) {
            for ((row, left, right) in segments(layout, link.start until link.end)) {
                val y = roundHalfUp(layout.getLineBaseline(row) / unit + 2) * unit
                drawRect(ink, Offset(left, y), Size(right - left, unit))
            }
        }
    }

    /** Where `range` lies on each line it takes: the line, and the left and right of its characters there. */
    private fun segments(layout: TextLayoutResult, range: IntRange): List<Triple<Int, Float, Float>> {
        if (range.isEmpty() || range.last >= layout.layoutInput.text.length) return emptyList()
        val first = layout.getLineForOffset(range.first)
        val last = layout.getLineForOffset(range.last)
        return (first..last).mapNotNull { line ->
            val start = max(range.first, layout.getLineStart(line))
            val end = min(range.last, layout.getLineEnd(line, visibleEnd = true) - 1)
            if (end < start) null else Triple(line, layout.getBoundingBox(start).left, layout.getBoundingBox(end).right)
        }
    }

    companion object {
        const val codePadding = 5f

        /** `--surface-muted`. */
        private val codeTint = Palette.surfaceMuted

        /** The marks a chip's and a link's characters carry, which every line of the text keeps. */
        private const val CHIP = "rc.md.chip"
        private const val LINK = "rc.md.link"

        private const val SPACER = "rc.md.spacer"
        private const val CHECKED = "rc.md.checked"
        private const val UNCHECKED = "rc.md.unchecked"

        fun build(inlines: List<MDInline>, size: Float, weight: MDWeight): MDInlineText {
            val builder = AnnotatedString.Builder()
            val used = mutableSetOf<String>()
            for ((index, inline) in inlines.withIndex()) {
                val nextIsCode = inlines.getOrNull(index + 1)?.isCode == true
                when (inline) {
                    is MDInline.Text -> words(builder, inline.value, inline.marks, size, weight, padsAfter = nextIsCode)
                    is MDInline.Code -> {
                        // Room at the start of a line for a chip's left padding, where there is no
                        // character before it to kern.
                        if (index == 0 || inlines[index - 1].isLineBreak) {
                            builder.appendInlineContent(SPACER, " ")
                            used += SPACER
                        }
                        code(builder, inline.value, inline.marks, size, weight)
                    }
                    MDInline.LineBreak -> builder.append("\n")
                    is MDInline.Checkbox -> {
                        val id = if (inline.checked) CHECKED else UNCHECKED
                        builder.appendInlineContent(id, if (inline.checked) "☑" else "☐")
                        used += id
                    }
                    is MDInline.Image -> {
                        val start = builder.length
                        builder.append(inline.alt)
                        builder.addStyle(SpanStyle(color = Palette.inkSecondary), start, builder.length)
                    }
                }
            }
            return MDInlineText(builder.toAnnotatedString(), inlineContent(used))
        }

        private fun inlineContent(used: Set<String>): Map<String, InlineTextContent> = used.associateWith { id ->
            when (id) {
                SPACER -> InlineTextContent(Placeholder(codePadding.sp, 1.sp, PlaceholderVerticalAlign.AboveBaseline)) {}
                else -> {
                    val checked = id == CHECKED
                    InlineTextContent(Placeholder(MDCheckbox.width.sp, MDCheckbox.side.sp, PlaceholderVerticalAlign.AboveBaseline)) {
                        MDCheckbox(checked)
                    }
                }
            }
        }

        /** A text run, with the kerning of its last character carrying the left padding of a code chip right after it. */
        private fun words(
            builder: AnnotatedString.Builder,
            value: String,
            marks: MDMarks,
            size: Float,
            base: MDWeight,
            padsAfter: Boolean,
        ) {
            if (value.isEmpty()) return
            val start = builder.length
            builder.append(value)
            val end = builder.length
            builder.addStyle(style(marks, size, base), start, end)
            if (padsAfter) ChatKerning.add(builder, codePadding, end - 1, end)
            link(builder, marks, start, end)
        }

        /** A code chip, its last character kerned by the right padding. */
        private fun code(
            builder: AnnotatedString.Builder,
            value: String,
            marks: MDMarks,
            size: Float,
            base: MDWeight,
        ) {
            if (value.isEmpty()) return
            val codeSize = size * 0.92f
            val weight = (marks.weight ?: base).fontWeight
            val start = builder.length
            builder.append(value)
            val end = builder.length
            builder.addStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = codeSize.sp,
                    fontWeight = weight,
                    fontStyle = if (marks.italic) FontStyle.Italic else null,
                    textDecoration = if (marks.strike) TextDecoration.LineThrough else null,
                ),
                start,
                end,
            )
            ChatKerning.add(builder, codePadding, end - 1, end)
            builder.addStringAnnotation(CHIP, "$codeSize:${weight.weight}", start, end)
            link(builder, marks, start, end)
        }

        private fun style(marks: MDMarks, size: Float, base: MDWeight): SpanStyle = SpanStyle(
            fontSize = if (marks.superscript) (size / 1.2f).sp else TextUnit.Unspecified,
            fontWeight = (marks.weight ?: base).fontWeight,
            fontStyle = if (marks.italic) FontStyle.Italic else null,
            textDecoration = if (marks.strike) TextDecoration.LineThrough else null,
            // `baselineOffset(size / 3)`, in the superscript's own size.
            baselineShift = if (marks.superscript) BaselineShift(0.4f) else null,
        )

        /** A link opens in the default browser; one the pipeline made safe to nothing is underlined all the same. */
        private fun link(builder: AnnotatedString.Builder, marks: MDMarks, start: Int, end: Int) {
            val link = marks.link ?: return
            builder.addStringAnnotation(LINK, link, start, end)
            if (link.isNotEmpty() && runCatching { URI(link) }.isSuccess) builder.addLink(LinkAnnotation.Url(link), start, end)
        }
    }
}
