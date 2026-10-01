package com.junbingao.remotecontrol.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Whether the tab bar stands at the foot of the screen. A conversation takes the whole screen,
 * tab bar included (`hideTabBar()` on the iPhone), so any screen that asks hides it for as long
 * as it is shown; a count rather than a flag, because the next screen appears before the last
 * one is gone.
 */
class TabBarVisibility {
    private var hiders by mutableIntStateOf(0)

    val isVisible: Boolean get() = hiders == 0

    internal fun hide() {
        hiders += 1
    }

    internal fun show() {
        hiders = (hiders - 1).coerceAtLeast(0)
    }
}

val LocalTabBarVisibility = compositionLocalOf<TabBarVisibility?> { null }

/** How much of the foot a screen leaves for the tab bar: the bar's own room, or none. */
val LocalTabBarReserve = compositionLocalOf { 0.dp }

/** `.toolbar(.hidden, for: .tabBar)`: the tab bar goes while this screen is on show. */
@Composable
fun HidesTabBar() {
    val visibility = LocalTabBarVisibility.current ?: return
    DisposableEffect(visibility) {
        visibility.hide()
        onDispose { visibility.show() }
    }
}

/** Room at the foot for a screen whose bar is [reserve] tall when shown. */
fun tabBarRoom(visible: Boolean, reserve: Dp): Dp = if (visible) reserve else 0.dp
