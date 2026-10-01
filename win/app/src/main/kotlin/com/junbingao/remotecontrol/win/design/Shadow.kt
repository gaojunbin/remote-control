package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

/**
 * A CSS `box-shadow`: one or more layers, each drawn on its own, as the browser draws them.
 * `--shadow-*` in `tokens.css`.
 */
class BoxShadow(val layers: List<Layer>) {
    class Layer(
        val color: Color,
        val x: Dp,
        val y: Dp,
        /** The CSS blur radius. The browser blurs with a Gaussian of half of it as its deviation. */
        val blur: Dp,
    )
}

object Shadow {
    /** `--shadow-1` */
    val one = BoxShadow(listOf(BoxShadow.Layer(Color.rgb(0, 0, 0, opacity = 0.06f), 0.dp, 1.dp, 2.dp)))

    /** `--shadow-soft`: a group surface is lifted off the canvas by this alone — never a border. */
    val soft = BoxShadow(
        listOf(
            BoxShadow.Layer(Color.rgb(24, 24, 22, opacity = 0.04f), 0.dp, 1.dp, 2.dp),
            BoxShadow.Layer(Color.rgb(24, 24, 22, opacity = 0.06f), 0.dp, 0.dp, 1.dp),
        ),
    )

    /** `--shadow-pop`: a popover panel. */
    val pop = BoxShadow(listOf(BoxShadow.Layer(Color.rgb(0, 0, 0, opacity = 0.1f), 0.dp, 8.dp, 24.dp)))

    /** `--shadow-modal`: a modal and the drawer. */
    val modal = BoxShadow(listOf(BoxShadow.Layer(Color.rgb(0, 0, 0, opacity = 0.12f), 0.dp, 24.dp, 60.dp)))
}

/**
 * The shadow of `shape` drawn under this view, one layer at a time, and the shape filled white
 * over them, as the Mac draws each layer as its own white copy of the shape with a shadow. Each
 * layer is its own blur of the shape, so two layers never shadow each other.
 */
fun Modifier.boxShadow(shadow: BoxShadow, shape: Shape): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    for (layer in shadow.layers) {
        val paint = Paint()
        paint.skiaPaint.apply {
            color = layer.color.toArgb()
            isAntiAlias = true
            maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, layer.blur.toPx() / 2)
        }
        translate(layer.x.toPx(), layer.y.toPx()) {
            drawIntoCanvas { it.drawOutline(outline, paint) }
        }
    }
    drawOutline(outline, Color.White)
}
