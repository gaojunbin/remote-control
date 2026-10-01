package com.junbingao.remotecontrol.android.system

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Bar
import com.junbingao.remotecontrol.android.design.Theme

/**
 * How far a bar's material reaches past its own bounds: a bottom bar paints down to the foot of
 * the screen under the tab bar, a top bar up to the top of the screen, because SwiftUI's
 * `.background(.bar)` ignores the safe area. The screen that places the bar says how far.
 */
val LocalBottomBarReach = compositionLocalOf { 0.dp }
val LocalTopBarReach = compositionLocalOf { 0.dp }

/**
 * `.safeAreaInset(edge: .bottom) { … .padding(.horizontal, Theme.Space.page).padding(.vertical,
 * Theme.Space.small).barBackground() }`, the bar every list ends in: its content 20 points in from
 * the sides and 10 from its edges, on the `.bar` material reaching down to the foot of the screen.
 */
@Composable
fun BottomBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val material = Bar.material
    val reach = LocalBottomBarReach.current
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind { drawRect(material, size = Size(size.width, size.height + reach.toPx())) }
            .padding(horizontal = Theme.Space.page, vertical = Theme.Space.small),
    ) { content() }
}

/**
 * A bar under the navigation bar — `.safeAreaInset(edge: .top)` with `.barBackground()` — whose
 * material reaches up to the top of the screen, over whatever the screen drew there.
 */
@Composable
fun TopBar(modifier: Modifier = Modifier, padding: Dp = 0.dp, content: @Composable () -> Unit) {
    val material = Bar.material
    val reach = LocalTopBarReach.current
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val up = reach.toPx()
                drawRect(material, topLeft = Offset(0f, -up), size = Size(size.width, size.height + up))
            }
            .padding(padding),
    ) { content() }
}
