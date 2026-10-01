package com.junbingao.remotecontrol.android.harness

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.junbingao.remotecontrol.android.shell.AppTab
import com.junbingao.remotecontrol.android.shell.MainShell
import com.junbingao.remotecontrol.android.shell.ShellState
import com.junbingao.remotecontrol.android.shell.TabRoot

/**
 * The real shell, opened on [tab] with [path] pushed over its root and [screen] drawing each
 * route, so a screen in a picture stands under the real tab bar and navigation bar, as it does on
 * the phone. The other tabs' roots draw nothing.
 */
@Composable
fun ShellAt(tab: AppTab, path: List<Any> = emptyList(), screen: @Composable (Any) -> Unit) {
    val state = remember {
        ShellState().apply {
            this.tab = tab
            if (path.isNotEmpty()) navigator.setPath(path)
        }
    }
    MainShell(state) { route ->
        if (route is TabRoot && route.tab != tab) Unit else screen(route)
    }
}
