package com.junbingao.remotecontrol.android.system

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The iPhone's safe area as the app lays its bars out against it: the status bar's height at
 * the top, the system navigation's at the bottom, and the keyboard's while it is up.
 *
 * The app draws edge to edge (`docs/DESIGN.md` § "The Android app"), so these are the system
 * bars' insets. A screenshot test provides the iPhone 17's own (62 at the top, 34 at the bottom),
 * which is what lets a picture of a screen line up with the iPhone's picture of it.
 */
data class SafeArea(
    val top: Dp,
    val bottom: Dp,
    val keyboard: Dp = 0.dp,
    val start: Dp = 0.dp,
    val end: Dp = 0.dp,
    /** The display's own bottom corner radius, which a sheet's foot follows; 0 on a square screen. */
    val displayCorner: Dp = 0.dp,
    /**
     * The part of the bottom that takes touches of its own: Android's three-button navigation.
     * Gesture navigation's handle, like the iPhone's home indicator, takes none, so it is 0 there.
     */
    val tappableBottom: Dp = 0.dp,
) {
    /** The bottom edge a view avoids when it must stay above the keyboard, as the composer does. */
    val bottomWithKeyboard: Dp get() = if (keyboard > bottom) keyboard else bottom

    companion object {
        /** The iPhone 17's safe area, portrait, for pictures that compare with the iPhone's. */
        val iPhone17 = SafeArea(top = 62.dp, bottom = 34.dp, displayCorner = 63.dp)
    }
}

/** A safe area a caller has fixed, or null to read the window's. */
val LocalSafeArea = compositionLocalOf<SafeArea?> { null }

/** The safe area in effect: the one provided, else the window's insets. */
@Composable
fun safeArea(): SafeArea {
    LocalSafeArea.current?.let { return it }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val bars = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    val navigation = WindowInsets.navigationBars
    val ime = WindowInsets.ime
    val tappable = WindowInsets.tappableElement
    val corner = displayCornerPx(LocalView.current)
    return with(density) {
        SafeArea(
            top = bars.getTop(this).toDp(),
            bottom = navigation.getBottom(this).toDp(),
            keyboard = ime.getBottom(this).toDp(),
            start = max(bars.getLeft(this, direction), navigation.getLeft(this, direction)).toDp(),
            end = max(bars.getRight(this, direction), navigation.getRight(this, direction)).toDp(),
            displayCorner = corner.toDp(),
            tappableBottom = tappable.getBottom(this).toDp(),
        )
    }
}

/** The window's bottom-left display corner, in pixels, where the platform says it (Android 12+). */
private fun displayCornerPx(view: View): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0
    return view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
}
