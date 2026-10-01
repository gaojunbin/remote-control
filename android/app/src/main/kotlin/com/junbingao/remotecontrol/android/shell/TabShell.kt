package com.junbingao.remotecontrol.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
 * The iPhone's `TabView` of three destinations — Devices, Sessions, Settings, in that order — each
 * its own navigation stack, under the floating tab bar, which a conversation hides. [destination]
 * draws a route of a tab's stack; the app's is `MainShell`'s, a picture's whatever it shows.
 */
@Composable
fun TabShell(navigation: ShellNavigation, destination: @Composable (tab: AppModel.Tab, route: Any) -> Unit) {
    val safe = safeArea()
    val visible = navigation.tabBar.isVisible
    val tabs = listOf(AppModel.Tab.devices, AppModel.Tab.sessions, AppModel.Tab.settings)
    val items = listOf(
        TabItem(L10n.string("Devices"), Sf.desktopcomputerFill, "tab.devices"),
        TabItem(L10n.string("Sessions"), Sf.bubbleLeftAndTextBubbleRightFill, "tab.sessions"),
        TabItem(L10n.string("Settings"), Sf.gearshapeFill, "tab.settings"),
    )
    val tab = navigation.tab
    val tabsState = rememberSaveableStateHolder()
    BackRouter(navigation.navigator)
    CompositionLocalProvider(
        LocalTabBarVisibility provides navigation.tabBar,
        LocalTabBarReserve provides if (visible) TabBarMetrics.reserved(safe.bottom) else safe.bottom,
    ) {
        Box(Modifier.fillMaxSize()) {
            // Each tab keeps what its screens saved while another tab is open, as the iPhone's tabs
            // stay where they were left.
            tabsState.SaveableStateProvider(tab.name) {
                NavigationStack(navigation.navigator(tab)) { route -> destination(tab, route) }
            }
            if (visible) {
                TabBar(
                    items,
                    selected = tabs.indexOf(tab),
                    onSelect = { index ->
                        val chosen = tabs[index]
                        // A second tap on the open tab goes back to its root, as UIKit's does.
                        if (chosen == navigation.tab) navigation.navigator.popToRoot() else navigation.tab = chosen
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
