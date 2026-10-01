package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.SfMetrics
import com.junbingao.remotecontrol.android.icons.SfSymbol
import com.junbingao.remotecontrol.android.icons.inkSpan

/**
 * SwiftUI's `Label(_:systemImage:)`: the symbol, then the words, set in the same font and colour.
 * The words start [LabelMetrics.gap] after the symbol's ink, measured from the iPhone's labels
 * ("Scroll sideways to read the table", "Add device", "New session"), and the gap follows the
 * phone's font size; lucide's own padding inside its box is taken out of it.
 */
@Composable
fun Label(title: String, symbol: SfSymbol, modifier: Modifier = Modifier, font: TextStyle = LocalFont.current) {
    val points = if (font.fontSize.isSp) font.fontSize.value else 17f
    val weight = font.fontWeight ?: FontWeight.Normal
    val side = SfMetrics.box(points.sp, symbol.scale)
    val ink = symbol.inkSpan(SfMetrics.strokeUnits(weight))
    val padding = (SfMetrics.GRID - ink.endInclusive) / SfMetrics.GRID * side
    val gap = with(LocalDensity.current) { LabelMetrics.gap(points).toDp() }
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy((gap - padding.dp).coerceAtLeast(0.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(symbol, font = font.weight(weight))
        Text(title, style = font)
    }
}

object LabelMetrics {
    /**
     * From the symbol's ink to the words, which hardly grows with the size: 9.7 points beside a
     * caption and 10.5 beside the body on the iPhone 17.
     */
    fun gap(points: Float) = (7.9f + 0.15f * points).sp
}
