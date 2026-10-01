package com.junbingao.remotecontrol.win.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import com.junbingao.remotecontrol.win.design.FaceRuns
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.TextStyle
import com.junbingao.remotecontrol.win.design.composeTextStyle
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource

/**
 * SwiftUI's `.lineLimit(1).truncationMode(.head)`: one line, and when the text does not fit, its
 * tail after an ellipsis — a path cut at its head keeps the folder it ends in (`docs/DESIGN.md`
 * § "The session row"). The width is the one the text is laid out at, known only once it is
 * measured, so the cut text follows a frame later, as `PushOut`'s joins do.
 */
@Composable
internal fun HeadTruncatedText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val shown = remember(text) { mutableStateOf(text) }
    val measurer = rememberTextMeasurer()
    val language = InterfaceLanguageSource.current
    val tracking = style.tracking * style.size
    val composeStyle = composeTextStyle(style.size, style.weight, style.mono, tracking, style.lineBox, Color.Black, TextAlign.Start, language)
    val fitting = modifier.layout { measurable, constraints ->
        if (constraints.hasBoundedWidth) {
            val fitted = HeadTruncation.fit(text) { candidate ->
                val faced = FaceRuns.apply(AnnotatedString(candidate), style.size, style.weight, style.mono, tracking, language)
                measurer.measure(faced, composeStyle, softWrap = false, maxLines = 1, density = this).size.width <= constraints.maxWidth
            }
            if (fitted != shown.value) shown.value = fitted
        }
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    Text(shown.value, style, fitting, color = color, lineLimit = 1)
}

/** The cut itself, apart from any text system: the longest tail that fits after an ellipsis. */
internal object HeadTruncation {
    const val ELLIPSIS = "…"

    fun fit(text: String, fits: (String) -> Boolean): String {
        if (fits(text)) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val keep = (low + high + 1) / 2
            if (fits(ELLIPSIS + tail(text, keep))) low = keep else high = keep - 1
        }
        return ELLIPSIS + tail(text, low)
    }

    /** The last `count` characters, never starting halfway through a surrogate pair. */
    private fun tail(text: String, count: Int): String {
        var start = text.length - count
        if (start in 1 until text.length && Character.isLowSurrogate(text[start])) start += 1
        return text.substring(start)
    }
}
