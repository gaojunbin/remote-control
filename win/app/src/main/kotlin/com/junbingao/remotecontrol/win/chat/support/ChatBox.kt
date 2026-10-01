package com.junbingao.remotecontrol.win.chat.support

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.junbingao.remotecontrol.win.design.Palette

/**
 * A box as the browser draws one: `border` wide on every side in `borderColor`, the corner
 * `radius` on its outer edge, the background under the border, and — for `overflow: hidden` —
 * what it holds clipped to the rounded padding box inside the border.
 */
fun Modifier.chatBox(
    radius: Dp,
    border: Dp = 1.dp,
    borderColor: Color = Palette.line,
    background: Color? = null,
    clips: Boolean = true,
): Modifier {
    val inner = RoundedCornerShape(max(0.dp, radius - border))
    return this
        .chatBorder(ChatBorder(width = border, radius = radius), borderColor)
        .then(if (background != null) Modifier.background(background, RoundedCornerShape(radius)) else Modifier)
        .padding(border)
        .then(if (clips) Modifier.clip(inner) else Modifier)
}
