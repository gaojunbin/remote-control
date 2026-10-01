package com.junbingao.remotecontrol.android.system

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.LocalAppearance

/**
 * The iPhone's Liquid Glass as its bars, bar buttons, menus and alerts wear it, measured from the
 * reference screenshots: a pale, faintly translucent fill, a hairline rim and a soft shadow that
 * lifts it off the page. The page under it shows through lightened; Android has no backdrop
 * blur in its UI toolkit, so what scrolls under a glass bar is not blurred as the iPhone blurs it.
 */
object Glass {
    val fill: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0xD92C2C2E) else Color(0x66FFFFFF)

    /**
     * A menu's glass: near white over the page, as measured on the reference screenshots. The
     * iPhone blurs what is under a menu and this cannot, so it is nearly opaque instead of letting
     * the words behind it show through.
     */
    val panel: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0xF72C2C2E) else Color(0xF7F9F9F9)

    /** An alert's glass over the dimmed screen: #EBE9E9 on the reference screenshots. */
    val alert: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0xFA2C2C2E) else Color(0xFAEBEAE9)

    /**
     * The crisp edge a glass button, a menu or an alert shows against the page: a line about a
     * pixel wide at 165 over the page's 245 on the reference screenshots. The tab bar has none.
     */
    val rim: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0x24FFFFFF) else Color(0x4D000000)

    /** The pressed or selected part of a glass control: the tab bar's pill. */
    val selection: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0x24FFFFFF) else Color(0x1A000000)
}

/** A glass surface in [shape]: bar buttons, and with no [rim] the tab bar. */
// The colours are the appearance's, read in composition; lint does not count a composable getter.
@SuppressLint("UnnecessaryComposedModifier")
fun Modifier.glass(shape: Shape, fill: Color? = null, rim: Boolean = true): Modifier = composed {
    val lifted = shadow(6.dp, shape, clip = false, ambientColor = Color(0x0A000000), spotColor = Color(0x1F000000))
        .background(fill ?: Glass.fill, shape)
    if (rim) lifted.border(0.5.dp, Glass.rim, shape) else lifted
}

/** A glass panel in [shape]: menus, and with [fill] alerts and action sheets. */
// The colours are the appearance's, read in composition; lint does not count a composable getter.
@SuppressLint("UnnecessaryComposedModifier")
fun Modifier.glassPanel(shape: Shape, fill: Color? = null): Modifier = composed {
    shadow(24.dp, shape, clip = false, ambientColor = Color(0x1F000000), spotColor = Color(0x29000000))
        .background(fill ?: Glass.panel, shape)
        .border(0.5.dp, Glass.rim, shape)
}
