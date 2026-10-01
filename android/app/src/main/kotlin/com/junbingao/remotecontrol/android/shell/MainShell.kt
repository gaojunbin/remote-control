package com.junbingao.remotecontrol.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.navigation.LocalTabBarReserve
import com.junbingao.remotecontrol.android.navigation.LocalTabBarVisibility
import com.junbingao.remotecontrol.android.navigation.NavigationStack
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.TabBar
import com.junbingao.remotecontrol.android.system.TabBarMetrics
import com.junbingao.remotecontrol.android.system.TabItem
import com.junbingao.remotecontrol.android.system.safeArea

/**
 * The iPhone's `MainShell`: three tabs — Devices, Sessions, Settings, in that order — each its
 * own navigation stack, under the floating tab bar, which a conversation hides. [destination]
 * draws a route; until the screens arrive (stage 3) the shell's own placeholders do.
 */
@Composable
fun MainShell(state: ShellState, destination: @Composable (Any) -> Unit = { ShellDestination(it) }) {
    val safe = safeArea()
    val visible = state.tabBar.isVisible
    val items = listOf(
        TabItem(L10n.string("Devices"), Sf.desktopcomputerFill, "tab.devices"),
        TabItem(L10n.string("Sessions"), Sf.bubbleLeftAndTextBubbleRightFill, "tab.sessions"),
        TabItem(L10n.string("Settings"), Sf.gearshapeFill, "tab.settings"),
    )
    BackRouter(state.presenter, state.navigator)
    CompositionLocalProvider(
        LocalTabBarVisibility provides state.tabBar,
        LocalTabBarReserve provides if (visible) TabBarMetrics.reserved(safe.bottom) else safe.bottom,
    ) {
        Box(Modifier.fillMaxSize()) {
            NavigationStack(state.navigator, destination = destination)
            if (visible) {
                TabBar(
                    items,
                    selected = state.tab.ordinal,
                    onSelect = { index ->
                        val chosen = AppTab.entries[index]
                        // A second tap on the open tab goes back to its root, as UIKit's does.
                        if (chosen == state.tab) state.navigator.popToRoot() else state.tab = chosen
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
