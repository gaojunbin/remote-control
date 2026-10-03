package com.junbingao.remotecontrol.android.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Amendment A47's red dot (`docs/DESIGN.md` § "A red dot for a session that stopped and waits for
 * you"): 8 points of the Danger red, centred in the row's leading gutter — [gutter] wide, ending
 * where this element starts — and on this element's own line. It is drawn, not laid out, so it
 * moves nothing when it comes or goes; what a screen reader hears is the row's to say.
 */
@Composable
fun Modifier.unseenDot(shown: Boolean, gutter: Dp = Theme.Space.medium): Modifier {
    if (!shown) return this
    val color = Theme.danger
    return drawBehind {
        drawCircle(color, radius = UnseenDot.size.toPx() / 2, center = Offset(-gutter.toPx() / 2, size.height / 2))
    }
}

object UnseenDot {
    val size: Dp = 8.dp
}
