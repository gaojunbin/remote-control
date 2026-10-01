package com.junbingao.remotecontrol.android.shell

import com.junbingao.remotecontrol.android.navigation.SessionLink
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shell's tabs and stacks, and a link that opens a conversation in place. */
class ShellStateTest {
    @Test
    fun theTabsAreTheIPhonesInItsOrder() {
        assertEquals(listOf(AppTab.devices, AppTab.sessions, AppTab.settings), AppTab.entries)
        val shell = ShellState()
        for (tab in AppTab.entries) assertEquals(listOf(TabRoot(tab)), shell.navigators.getValue(tab).routes)
    }

    @Test
    fun aLinkOpensItsConversationOnTheSessionsTabOverTheList() {
        val shell = ShellState()
        shell.tab = AppTab.devices
        val held = SessionLink("mac", "held")
        val opened = SessionLink("mac", "opened")
        shell.open(held)
        assertEquals(AppTab.sessions, shell.tab)
        shell.open(opened)
        val sessions = shell.navigators.getValue(AppTab.sessions)
        assertEquals("a link replaces the conversation it found open", listOf(TabRoot(AppTab.sessions), ConversationRoute(opened)), sessions.routes)
        sessions.pop()
        assertEquals("and Back returns to the list", listOf(TabRoot(AppTab.sessions)), sessions.routes)
    }

    @Test
    fun eachTabKeepsItsOwnStack() {
        val shell = ShellState()
        shell.tab = AppTab.devices
        shell.navigator.push("mac-studio-office")
        shell.tab = AppTab.settings
        shell.navigator.push("users")
        shell.tab = AppTab.devices
        assertEquals(listOf(TabRoot(AppTab.devices), "mac-studio-office"), shell.navigator.routes)
        shell.openGallery()
        assertEquals(listOf(TabRoot(AppTab.settings), GalleryRoute), shell.navigator.routes)
    }
}
