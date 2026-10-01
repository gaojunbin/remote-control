package com.junbingao.remotecontrol.android.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/** The shell's tabs and stacks: a stack per tab, each starting from its tab's root. */
class ShellNavigationTest {
    @Test
    fun everyTabHasAStackFromItsOwnRoot() {
        val navigation = ShellNavigation()
        for (tab in AppModel.Tab.entries) assertEquals(listOf(TabRoot(tab)), navigation.navigator(tab).routes)
        assertEquals("the app opens on the conversations", AppModel.Tab.sessions, navigation.tab)
    }

    @Test
    fun eachTabKeepsItsOwnStack() {
        val navigation = ShellNavigation()
        navigation.tab = AppModel.Tab.devices
        navigation.navigator.push("mac-studio-office")
        navigation.tab = AppModel.Tab.settings
        navigation.navigator.push("users")
        navigation.tab = AppModel.Tab.devices
        assertEquals(listOf(TabRoot(AppModel.Tab.devices), "mac-studio-office"), navigation.navigator.routes)
        assertEquals(listOf(TabRoot(AppModel.Tab.settings), "users"), navigation.navigator(AppModel.Tab.settings).routes)
    }
}
