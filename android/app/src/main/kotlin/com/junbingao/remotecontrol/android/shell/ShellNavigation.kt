package com.junbingao.remotecontrol.android.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.android.navigation.Navigator
import com.junbingao.remotecontrol.android.navigation.TabBarVisibility

/** A tab's root screen, the value its stack starts from. */
data class TabRoot(val tab: AppModel.Tab)

/**
 * Where the shell is: the tab that is open, a navigation stack per tab, and whether the tab bar
 * stands at the foot. The iPhone keeps the first two in `AppModel` (`tab`, `path`, `devicePath`)
 * and SwiftUI keeps the third; here [AppModel] holds one of these, and a picture of a screen can
 * hold one of its own without a model.
 */
class ShellNavigation {
    var tab: AppModel.Tab by mutableStateOf(AppModel.Tab.sessions)
    val navigators: Map<AppModel.Tab, Navigator> = AppModel.Tab.entries.associateWith { Navigator(TabRoot(it)) }
    val tabBar = TabBarVisibility()

    /** The stack of the tab that is open. */
    val navigator: Navigator get() = navigators.getValue(tab)

    fun navigator(tab: AppModel.Tab): Navigator = navigators.getValue(tab)
}
