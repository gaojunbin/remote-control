package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * The Mac's `.css(…)` on a view that holds text rather than on a text — a row of a label and a
 * number, say: the texts inside take the style's font on the line SwiftUI gives it, and the row
 * they make is set on the browser's baselines as one line box (`cssLineBox`). Its first baseline,
 * the highest of what it holds, sits `baseline` below the box's top, and the box ends one line
 * below its last, the lowest; the box lands on a whole point. `content` is laid out as a `Box`'s.
 */
@Composable
fun CSSLine(style: TextStyle, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val exact = remember { ExactHeight() }
    WithFont(style.size, style.weight, style.mono) {
        Box(modifier.cssLineBox(style.lineBox, style.baseline, snapToPoint = true, exact = exact).exactHeight(exact)) { content() }
    }
}
