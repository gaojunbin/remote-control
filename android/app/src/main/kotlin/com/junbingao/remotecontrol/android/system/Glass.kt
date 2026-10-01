package com.junbingao.remotecontrol.android.system

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.LocalAppearance

/**
 * The iPhone's Liquid Glass as its bars, bar buttons, menus and alerts wear it, measured from the
 * reference screenshots: what is under it blurred and lightened ([blurredBackdrop]), a crisp rim
 * that darkens where the edge turns away from the eye, and a soft shadow round it. Where nothing
 * is blurred — before Android 12, or over nothing recorded — the fill is denser instead, so the
 * words under a bar never read through it.
 */
object Glass {
    val fill: Color @Composable @ReadOnlyComposable
        get() = when {
            LocalAppearance.current.isDark -> Color(0xD92C2C2E)
            blursHere -> Color(0x8CFFFFFF)
            else -> Color(0xD9FFFFFF)
        }

    /**
     * A menu's glass: near white over the page, as measured on the reference screenshots. A menu
     * stands over whatever is presented, which nothing records for it, so it is nearly opaque
     * instead of letting the words behind it show through.
     */
    val panel: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0xF72C2C2E) else Color(0xF7F9F9F9)

    /** An alert's glass over the dimmed screen: #EBE9E9 on the reference screenshots. */
    val alert: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0xFA2C2C2E) else Color(0xFAEBEAE9)

    /**
     * The rim, where the edge runs across the eye — a bar button's top and foot, the tab bar's
     * long sides: a faint line, 226 over the page's 250 on the reference screenshots.
     */
    val rim: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0x24FFFFFF) else Color(0x17000000)

    /** The rim where the edge turns away, at a capsule's round ends: 172 over the page's 250. */
    val rimTurned: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0x33FFFFFF) else Color(0x4D000000)

    /** The pressed or selected part of a glass control: an alert's pressed button, a menu row. */
    val selection: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0x24FFFFFF) else Color(0x1A000000)

    /**
     * The tab bar's pill, darker at its top than at its foot as the glass bends the light: 220
     * to 230 over the bar's 250 on the reference screenshots.
     */
    val pill: Brush @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) {
            Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x1FFFFFFF)))
        } else {
            Brush.verticalGradient(0f to Color(0x1F000000), 0.45f to Color(0x17000000), 1f to Color(0x12000000))
        }
}

/** A glass surface in [shape]: bar buttons, the tab bar, a search field's capsule. */
// The colours are the appearance's, read in composition; lint does not count a composable getter.
@SuppressLint("UnnecessaryComposedModifier")
fun Modifier.glass(shape: Shape, fill: Color? = null, rim: Boolean = true): Modifier = composed {
    val tint = fill ?: Glass.fill
    val lit = Glass.rim
    val turned = Glass.rimTurned
    outerShadow(shape)
        .blurredBackdrop(shape)
        .background(tint, shape)
        .then(if (rim) Modifier.glassRim(shape, lit, turned) else Modifier)
}

/** A glass panel in [shape]: menus, and with [fill] alerts and action sheets. */
// The colours are the appearance's, read in composition; lint does not count a composable getter.
@SuppressLint("UnnecessaryComposedModifier")
fun Modifier.glassPanel(shape: Shape, fill: Color? = null): Modifier = composed {
    shadow(24.dp, shape, clip = false, ambientColor = Color(0x1F000000), spotColor = Color(0x29000000))
        .background(fill ?: Glass.panel, shape)
        .border(0.5.dp, Glass.rimTurned, shape)
}

/**
 * The rim, lighter along the edges that run across the eye and darker round the ends where the
 * edge turns away, as the iPhone's glass catches the light.
 */
private fun Modifier.glassRim(shape: Shape, lit: Color, turned: Color): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val end = (minOf(size.width, size.height) / 2 / size.width).coerceIn(0.001f, 0.5f)
    val brush = Brush.horizontalGradient(0f to turned, end to lit, 1f - end to lit, 1f to turned)
    val line = Stroke(width = 0.5.dp.toPx())
    onDrawWithContent {
        drawContent()
        drawOutline(outline, brush, style = line)
    }
}

/**
 * A soft shadow under the glass, drawn only outside its shape, so a glass that lets the page
 * through never shows its own shadow inside it.
 */
private fun Modifier.outerShadow(shape: Shape): Modifier = drawBehind {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = GlassShadow.toArgb()
        maskFilter = android.graphics.BlurMaskFilter(6.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    clipPath(path, ClipOp.Difference) {
        translate(top = 2.dp.toPx()) {
            drawIntoCanvas { it.nativeCanvas.drawPath(path.asAndroidPath(), paint) }
        }
    }
}

/** The shadow under the glass: 247 against the page's 250 just below a bar button. */
private val GlassShadow = Color(0x0D000000)
