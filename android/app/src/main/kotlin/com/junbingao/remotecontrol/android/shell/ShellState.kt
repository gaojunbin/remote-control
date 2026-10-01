package com.junbingao.remotecontrol.android.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.android.navigation.Navigator
import com.junbingao.remotecontrol.android.navigation.SessionLink
import com.junbingao.remotecontrol.android.navigation.TabBarVisibility
import com.junbingao.remotecontrol.android.system.Presenter

/** The three destinations, in the iPhone's order (`docs/IOS.md` § "The shell"). */
enum class AppTab { devices, sessions, settings }

/** A tab's root screen, the value its stack starts from. */
data class TabRoot(val tab: AppTab)

/** A conversation a link or a notification asked for; stage 3 draws it as the chat. */
data class ConversationRoute(val link: SessionLink)

/** The debug gallery of the design system's primitives, and one of its pages. */
data object GalleryRoute

data class GalleryPageRoute(val page: String)

/**
 * The shell's own state, which outlives any one screen: the tab that is open, a navigation stack
 * per tab, whether the tab bar is showing, and what is presented over it all. One per activity.
 */
class ShellState {
    var tab by mutableStateOf(AppTab.sessions)
    val navigators: Map<AppTab, Navigator> = AppTab.entries.associateWith { Navigator(TabRoot(it)) }
    val tabBar = TabBarVisibility()
    val presenter = Presenter()

    val navigator: Navigator get() = navigators.getValue(tab)

    /**
     * A link into a conversation opens it in place, as the iPhone's `handle(_ link:)` does: the
     * Sessions tab, with the conversation as its only screen over the list.
     */
    fun open(link: SessionLink) {
        tab = AppTab.sessions
        navigators.getValue(AppTab.sessions).setPath(listOf(ConversationRoute(link)))
    }

    /** The debug entry: the gallery over the Settings tab. */
    fun openGallery() {
        tab = AppTab.settings
        navigators.getValue(AppTab.settings).setPath(listOf(GalleryRoute))
    }
}
