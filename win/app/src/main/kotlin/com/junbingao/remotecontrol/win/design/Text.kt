package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.junbingao.remotecontrol.win.standin.InterfaceLanguage
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/**
 * A text, set as a CSS rule sets it when it has a `style` — `font-size`, `font-weight`,
 * `line-height`, `letter-spacing`, the monospaced face for `mono` — on the browser's baselines
 * (`cssLineBox`), and otherwise in the environment's font (`WithFont`) on the line SwiftUI gives
 * the Mac's text. It is drawn in the environment's ink unless it names a colour.
 *
 * `lineLimit` is SwiftUI's: that many lines, the last cut short with an ellipsis.
 */
@Composable
fun Text(
    text: String,
    style: TextStyle? = null,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Start,
    lineLimit: Int? = null,
    softWrap: Boolean = true,
) {
    Text(AnnotatedString(text), style, modifier, color, textAlign, lineLimit, softWrap)
}

/**
 * The same for text with styles of its own. A span's `fontWeight`, its `fontSize` and a
 * `FontFamily.Monospace` choose the face its characters are set in, as the Mac's type does.
 */
@Composable
fun Text(
    text: AnnotatedString,
    style: TextStyle? = null,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Start,
    lineLimit: Int? = null,
    softWrap: Boolean = true,
) {
    val environment = LocalFont.current
    val size = style?.size ?: environment.size
    val weight = style?.weight ?: environment.weight
    val mono = style?.mono ?: environment.mono
    val tracking = (style?.tracking ?: 0f) * size
    val language = InterfaceLanguageSource.current
    val ink = if (color.isSpecified) color else LocalContentColor.current
    val faced = remember(text, size, weight, mono, tracking, language) {
        FaceRuns.apply(text, size, weight, mono, tracking, language)
    }
    val line = if (style != null) {
        LineGeometry(style.lineBox, style.baseline, snapToPoint = true)
    } else {
        NaturalLine.of(size, weight, mono).let { LineGeometry(it.height, it.baseline, snapToPoint = false) }
    }
    // One colour for the whole text: its glyphs are drawn in the neutral grey and recoloured.
    val neutral = TextRendering.neutralInk?.takeIf { text.spanStyles.none { it.item.color.isSpecified || it.item.brush != null } }
    val composeStyle = composeTextStyle(size, weight, mono, tracking, line.lineBox, neutral ?: ink, textAlign, language)
    val wraps = softWrap && lineLimit != 1
    val maxLines = lineLimit ?: Int.MAX_VALUE
    val overflow = if (lineLimit != null) TextOverflow.Ellipsis else TextOverflow.Clip
    val measurer = rememberTextMeasurer()
    val shown = remember(faced, maxLines, wraps) { mutableStateOf(faced) }
    val exact = remember { ExactHeight() }
    BasicText(
        text = shown.value,
        modifier = modifier
            .cssLineBox(line.lineBox, line.baseline, line.snapToPoint, exact)
            .then(if (neutral != null) Modifier.recoloured(ink) else Modifier)
            .then(if (wraps) Modifier.pushingOut(faced, composeStyle, maxLines, overflow, measurer, shown) else Modifier)
            .exactHeight(exact),
        style = composeStyle,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = wraps,
    )
}

/** What Compose is given to set a text in: the primary face, its tracking and the line box. */
internal fun composeTextStyle(
    size: Float,
    weight: FontWeight,
    mono: Boolean,
    tracking: Float,
    lineBox: Float,
    color: Color,
    textAlign: TextAlign,
    language: InterfaceLanguage,
): ComposeTextStyle {
    val primary = SystemFace.face(size, weight, mono)
    return ComposeTextStyle(
        color = color,
        fontSize = size.sp,
        fontFamily = primary.family,
        letterSpacing = (primary.tracking + tracking).sp,
        lineHeight = lineBox.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        textAlign = textAlign,
        platformStyle = TextRendering.platformStyle,
        localeList = LocaleList(language.rawValue),
    )
}

private data class LineGeometry(val lineBox: Float, val baseline: Float, val snapToPoint: Boolean)

private val Color.isSpecified: Boolean get() = this != Color.Unspecified

/**
 * Which face sets which characters. The Latin face sets everything it has a glyph for; Chinese it
 * has none for falls to the interface language's Chinese face (`SystemFace.chinese`), each face
 * with its own tracking; anything else is left to the system's fallback. A span's weight, size or
 * monospaced family chooses its own faces, and its weight is taken off the span so Skia never
 * thickens a face that already has it.
 */
internal object FaceRuns {
    fun apply(text: AnnotatedString, size: Float, weight: FontWeight, mono: Boolean, tracking: Float, language: InterfaceLanguage): AnnotatedString {
        val restyled = text.spanStyles.any { it.item.fontWeight != null || it.item.fontFamily != null || it.item.fontSize.isSpecifiedSp }
        if (!restyled && text.text.all { it.code < 0x2000 }) return text
        val builder = AnnotatedString.Builder(text.text)
        for (span in text.spanStyles) {
            builder.addStyle(span.item.copy(fontWeight = null, fontFamily = null), span.start, span.end)
        }
        for (range in text.paragraphStyles) builder.addStyle(range.item, range.start, range.end)
        var index = 0
        while (index < text.length) {
            val codePoint = text.text.codePointAt(index)
            val next = index + Character.charCount(codePoint)
            val spec = specAt(text, index, size, weight, mono)
            val face = faceFor(codePoint, spec, language)
            var end = next
            while (end < text.length) {
                val point = text.text.codePointAt(end)
                if (specAt(text, end, size, weight, mono) != spec || faceFor(point, spec, language) !== face) break
                end += Character.charCount(point)
            }
            // `letter-spacing` in em is worked out where it is declared and inherited as a length.
            builder.addStyle(SpanStyle(fontFamily = face.family, letterSpacing = (face.tracking + tracking).sp), index, end)
            index = end
        }
        return builder.toAnnotatedString()
    }

    private fun specAt(text: AnnotatedString, index: Int, size: Float, weight: FontWeight, mono: Boolean): FontSpec {
        var spec = FontSpec(size, weight, mono)
        for (span in text.spanStyles) {
            if (index < span.start || index >= span.end) continue
            val style = span.item
            if (style.fontWeight != null) spec = spec.copy(weight = style.fontWeight!!)
            if (style.fontFamily == FontFamily.Monospace) spec = spec.copy(mono = true)
            if (style.fontSize.isSpecifiedSp) spec = spec.copy(size = style.fontSize.value)
        }
        return spec
    }

    private fun faceFor(codePoint: Int, spec: FontSpec, language: InterfaceLanguage): Face {
        val primary = SystemFace.face(spec.size, spec.weight, spec.mono)
        if (codePoint < 0x2000 || primary.draws(codePoint)) return primary
        val chinese = SystemFace.chinese(spec.size, spec.weight, language) ?: return primary
        return if (chinese.draws(codePoint)) chinese else primary
    }

    private val androidx.compose.ui.unit.TextUnit.isSpecifiedSp: Boolean get() = type == androidx.compose.ui.unit.TextUnitType.Sp
}
