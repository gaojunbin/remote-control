package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/** Type and views measured the way `Text` and the window lay them out, at 1 CSS px to the pixel. */
internal object Measure {
    val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    /** A text set in `style`, laid out on one line, or wrapped at `width`. */
    fun layout(text: String, style: TextStyle, language: InterfaceLanguage = InterfaceLanguage.en, width: Int? = null): TextLayoutResult {
        val tracking = style.tracking * style.size
        val faced = FaceRuns.apply(AnnotatedString(text), style.size, style.weight, style.mono, tracking, language)
        return measurer.measure(
            faced,
            composeTextStyle(style.size, style.weight, style.mono, tracking, style.lineBox, Color.Black, TextAlign.Start, language),
            softWrap = width != null,
            constraints = if (width != null) Constraints(maxWidth = width) else Constraints(),
        )
    }

    /** The advance of one line of `text`, in CSS px. */
    fun width(text: String, style: TextStyle, language: InterfaceLanguage = InterfaceLanguage.en): Float =
        layout(text, style, language).getLineRight(0)

    /** The size a view takes when nothing bounds it. */
    fun size(content: @Composable () -> Unit): IntSize {
        var size = IntSize.Zero
        val scene = ImageComposeScene(2000, 2000, Density(1f)) {
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)) {
                Box(Modifier.onGloballyPositioned { size = it.size }) { content() }
            }
        }
        scene.render()
        scene.close()
        return size
    }
}
