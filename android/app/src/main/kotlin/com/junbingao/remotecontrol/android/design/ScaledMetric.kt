package com.junbingao.remotecontrol.android.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified

/**
 * `@ScaledMetric(relativeTo:)`: a box that grows with the text beside it. The phone's font size
 * scales sp, non-linearly on newer Android, so the box follows the style's own size in dp rather
 * than a flat factor, and grows exactly as that line does.
 */
@Composable
fun scaledMetric(value: Dp, relativeTo: TextStyle): Dp {
    val size = relativeTo.fontSize
    if (!size.isSpecified) return value
    val scaled = with(LocalDensity.current) { size.toDp() }
    return value * (scaled.value / size.value)
}
