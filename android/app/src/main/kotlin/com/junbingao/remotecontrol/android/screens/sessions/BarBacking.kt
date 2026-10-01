package com.junbingao.remotecontrol.android.screens.sessions

import android.annotation.SuppressLint
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Bar
import com.junbingao.remotecontrol.android.design.Theme

/**
 * What the iPhone's `.bar` material looks like with a list scrolled under it: the rows behind it
 * blurred into the page, so the bar reads as one even surface. Android draws no blur, so a bar over
 * a scrolling list is backed with the page: under the bar's own material, and [down] past it to the
 * foot of the screen as far as that material goes, it shows exactly what it shows at rest, and the
 * rows passing under it are not read through it. [up] carries the bar's colour above it to the top
 * of the screen, where no material of its own is drawn, faded in by [strength] — for a bar whose
 * reach grows as a title folds away.
 */
// The colours are the appearance's, read in composition; lint does not count a composable getter.
@SuppressLint("UnnecessaryComposedModifier")
fun Modifier.barBacking(up: Dp = 0.dp, down: Dp = 0.dp, strength: Float = 1f): Modifier = composed {
    val page = Theme.canvas
    val bar = Bar.material.compositeOver(page)
    drawBehind {
        drawRect(page, size = Size(size.width, size.height + down.toPx()))
        val above = up.toPx()
        if (above > 0f && strength > 0f) {
            drawRect(bar.copy(alpha = strength), topLeft = Offset(0f, -above), size = Size(size.width, above))
        }
    }
}
