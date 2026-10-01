package com.junbingao.remotecontrol.android.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * How wide the widest of [texts] is when [Text] sets it on one line in [style]: Latin tracked
 * towards SF's widths, Chinese untracked, in the interface language's locale. For the controls
 * UIKit sizes by their words before drawing them — an alert's buttons side by side or stacked, a
 * row of swipe buttons as wide as their longest name.
 */
@Composable
fun widestLine(texts: List<String>, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val locale = interfaceLocale()
    val widest = texts.maxOfOrNull { text ->
        val tracking = if (holdsCjk(text)) TextUnit.Unspecified else style.letterSpacing
        measurer.measure(text, style.copy(localeList = locale, letterSpacing = tracking), softWrap = false, maxLines = 1).size.width
    } ?: return 0.dp
    return with(LocalDensity.current) { widest.toDp() }
}
