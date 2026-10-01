package com.junbingao.remotecontrol.android.system

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * What a bar's material and a glass control blur: the content drawn under them, as UIKit's
 * materials read the pixels behind a bar. [BackdropSource] records its content once a frame and
 * draws it as usual; a surface drawn after it, over it, draws that recording again, blurred, inside
 * its own shape ([blurredBackdrop]). Android blurs a recording from Android 12; below that the
 * surfaces keep their plain translucent fill, which [Backdrop.blurs] tells them to make denser.
 */
class Backdrop {
    internal var layer: GraphicsLayer? = null

    /** Where the recorded content stands, in the window, for a surface to line its copy up with. */
    internal var origin by mutableStateOf(Offset.Zero)

    companion object {
        /** Whether this phone blurs a recording at all. */
        val blurs: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}

/** The backdrop the surfaces at this point of the screen stand over, or null where there is none to blur. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Whether a surface drawn here blurs what is under it, rather than covering it with a denser fill. */
val blursHere: Boolean
    @Composable @ReadOnlyComposable get() = Backdrop.blurs && LocalBackdrop.current != null

/**
 * Draws [content] and records it as [backdrop] for the surfaces drawn over it. Nothing inside the
 * content blurs this recording — a surface cannot blur a picture of itself — so the content sees
 * no backdrop of its own unless it records one inside.
 */
@Composable
fun BackdropSource(backdrop: Backdrop, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    backdrop.layer = layer
    Box(
        modifier
            .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
            .drawWithContent {
                if (Backdrop.blurs) {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                } else {
                    drawContent()
                }
            },
    ) {
        CompositionLocalProvider(LocalBackdrop provides null) { content() }
    }
}

/** Draws [content] with [backdrop] as what its surfaces stand over. */
@Composable
fun OverBackdrop(backdrop: Backdrop, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBackdrop provides backdrop, content = content)
}

/**
 * Behind this element, inside [shape], what the backdrop holds under it, blurred by [radius]. The
 * copy is recorded a radius wider than the element on every side, so its edges blur what lies just
 * outside as the iPhone's do, rather than fading to nothing. Does nothing where nothing is blurred.
 */
fun Modifier.blurredBackdrop(shape: Shape, radius: Dp = GlassMetrics.blur): Modifier = composed {
    val backdrop = LocalBackdrop.current
    if (backdrop == null || !Backdrop.blurs) return@composed this
    val blurred = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind {
            val source = backdrop.layer ?: return@drawBehind
            val blur = radius.toPx()
            val margin = ceil(blur * 2).toInt()
            val shift = backdrop.origin - origin
            blurred.renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
            blurred.topLeft = IntOffset(-margin, -margin)
            blurred.record(IntSize(size.width.toInt() + margin * 2, size.height.toInt() + margin * 2)) {
                translate(shift.x + margin, shift.y + margin) { drawLayer(source) }
            }
            val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
            clipPath(outline) { drawLayer(blurred) }
        }
}

/** How the glass blurs, from the iPhone 17 reference screenshots. */
object GlassMetrics {
    /** What a bar or a glass control shows of the content under it: a shape, never a word. */
    val blur = 8.dp
}
