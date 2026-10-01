package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.runtime.Composable
import com.junbingao.remotecontrol.android.screens.users.UsersRoute
import com.junbingao.remotecontrol.android.screens.users.UsersView
import com.junbingao.remotecontrol.android.shell.TabRoot

/**
 * What the Settings tab's stack draws for a route: Settings at the root and the pages it pushes —
 * the accounts screen, and whatever else the Settings port pushes of its own.
 */
@Composable
fun SettingsDestination(route: Any) {
    when (route) {
        is TabRoot -> SettingsView()
        UsersRoute -> UsersView()
    }
}
