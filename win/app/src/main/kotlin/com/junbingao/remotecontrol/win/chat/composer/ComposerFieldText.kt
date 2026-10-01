package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.design.FaceRuns
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.PrimaryBaseline
import com.junbingao.remotecontrol.win.design.TextStyle
import com.junbingao.remotecontrol.win.design.composeTextStyle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/**
 * How `.composer-input` sets its words: 14 px, a line box of 21 (the body's `line-height: 1.5`),
 * 7 px above the first line and below the last, and each line's baseline where the browser puts it
 * in its box.
 */
object ComposerFieldText {
    val style = TextStyle(size = FontSize.fs14)
    val lineBox: Float get() = style.lineBox
    const val padding = 7f

    /** `min-height: 34px` under one line of 21 and its padding, which is 35. */
    val minHeight: Float get() = lineBox + 2 * padding

    /** `max-height: 220px`: past it the field scrolls inside. */
    const val maxHeight = 220f

    /** The field's height for these words at this width: one box per line, the padding, and the web's floor and ceiling. */
    fun height(ofContent: Float): Float = min(max(minHeight, ofContent + 2 * padding), maxHeight)

    /** What Compose is given to set the field's words in: the system face, every line one box tall. */
    internal fun composeStyle(color: Color, language: InterfaceLanguage): ComposeTextStyle =
        composeTextStyle(style.size, style.weight, style.mono, 0f, lineBox, color, TextAlign.Start, language)

    /**
     * How far, in pixels, the field's words move down so every line's baseline sits where the
     * browser puts it in its box: Compose centres the face in the line, the browser rounds its
     * ascent and descent first. Every line is one box tall, so one shift puts them all in place.
     */
    internal fun baselineShift(composeStyle: ComposeTextStyle, measurer: TextMeasurer, density: Density): Int {
        val own = PrimaryBaseline.of(composeStyle, style.weight, style.mono, lineBox, measurer, density)
        return (style.baseline * density.density).roundToInt() - own
    }
}

/**
 * The field's words with each run in the face that has its glyphs — Chinese in the interface
 * language's Chinese face — as every other text of the app is set (`FaceRuns`). The characters are
 * the field's own, so the caret's offsets are too.
 */
internal class FaceRunsTransformation(private val language: InterfaceLanguage) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val style = ComposerFieldText.style
        return TransformedText(FaceRuns.apply(text, style.size, FontWeight.Normal, style.mono, 0f, language), OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is FaceRunsTransformation && other.language == language

    override fun hashCode(): Int = language.hashCode()
}
