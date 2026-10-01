package com.junbingao.remotecontrol.win.chat.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.hex

/**
 * A task list item's `<input type="checkbox" disabled>` as Chrome draws its own controls: a
 * 13-point box with a 2-point radius — a light edge on an almost-white fill, or a grey fill with a
 * light check once done — its bottom on the baseline, 4 points of margin before it and 3 after.
 */
@Composable
fun MDCheckbox(checked: Boolean) {
    Canvas(Modifier.size(MDCheckbox.width.dp, MDCheckbox.side.dp)) {
        val unit = density
        val side = MDCheckbox.side * unit
        val left = MDCheckbox.leading * unit
        val edge = Color.hex(0xD1D1D1)
        if (checked) {
            drawRoundRect(edge, Offset(left, 0f), Size(side, side), CornerRadius(2 * unit, 2 * unit))
            // Chrome's check: from a fifth across and halfway down, a fifth over and down, then up
            // to a fifth from the far corner.
            val check = Path().apply {
                moveTo(left + side * 0.2f, side * 0.5f)
                lineTo(left + side * 0.4f, side * 0.7f)
                lineTo(left + side - side * 0.2f, side * 0.2f)
            }
            drawPath(check, Color.hex(0xEDEDED), style = Stroke(width = side * 0.16f))
        } else {
            val inset = 0.5f * unit
            val inner = Size(side - 2 * inset, side - 2 * inset)
            val radius = CornerRadius(1.5f * unit, 1.5f * unit)
            drawRoundRect(Color.hex(0xF8F8F8), Offset(left + inset, inset), inner, radius)
            drawRoundRect(edge, Offset(left + inset, inset), inner, radius, style = Stroke(width = unit))
        }
    }
}

object MDCheckbox {
    const val leading = 4f
    const val trailing = 3f
    const val side = 13f

    /** The room it takes in its line, margins included. */
    const val width = leading + side + trailing
}
