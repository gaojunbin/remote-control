package com.junbingao.remotecontrol.win.devices.adddevice

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import com.junbingao.remotecontrol.win.design.ExactHeight
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.TextStyle
import com.junbingao.remotecontrol.win.design.exactHeight
import kotlin.math.roundToInt

/**
 * `.textSelection(.enabled)` on a text set in a CSS rule: a part of it can be selected and copied.
 * Compose's selection lays the text out in a layout of its own, which would drop the fraction of a
 * pixel a text of 1.7 lines leaves (`ExactHeight`); its lines are all one box tall, so the exact
 * height is the line count times the box, and it is handed on from here.
 */
@Composable
internal fun SelectableText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val exact = remember { ExactHeight() }
    Layout({ SelectionContainer { Text(text, style, color = color) } }, modifier.exactHeight(exact)) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints)
        val line = style.lineBox * density
        val lines = (placeable.height / line).roundToInt().coerceAtLeast(1)
        exact.fraction = lines * line - placeable.height
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
