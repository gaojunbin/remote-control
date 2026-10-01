package com.junbingao.remotecontrol.android.harness

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.junbingao.remotecontrol.android.shell.AppModel
import com.junbingao.remotecontrol.android.shell.ShellNavigation
import com.junbingao.remotecontrol.android.shell.TabShell

/**
 * The real shell, opened on [tab] with [path] pushed over its root and [screen] drawing each
 * route, so a screen in a picture stands under the real tab bar and navigation bar, as it does on
 * the phone — without a model, for a screen drawn from values. The other tabs draw nothing.
 */
@Composable
fun ShellAt(tab: AppModel.Tab, path: List<Any> = emptyList(), screen: @Composable (Any) -> Unit) {
    val navigation = remember {
        ShellNavigation().apply {
            this.tab = tab
            if (path.isNotEmpty()) navigator.setPath(path)
        }
    }
    TabShell(navigation) { shown, route -> if (shown == tab) screen(route) }
}
