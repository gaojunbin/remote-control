package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp

/**
 * `.surface`: a list of rows on one soft surface — white, a 16 px radius, lifted off the canvas by
 * `--shadow-soft` alone and clipping what it holds. Rows inside it are parted by spacing, never a
 * hairline.
 */
fun Modifier.surface(cornerRadius: Dp = Radius.lg): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return boxShadow(Shadow.soft, shape).clip(shape).background(Palette.surface)
}

/** `.card`: the same surface without the clip, for a block that is not a list. */
fun Modifier.card(cornerRadius: Dp = Radius.lg, shadow: BoxShadow = Shadow.soft): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return boxShadow(shadow, shape).background(Palette.surface, shape)
}
