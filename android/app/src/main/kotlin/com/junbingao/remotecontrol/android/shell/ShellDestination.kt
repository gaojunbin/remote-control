package com.junbingao.remotecontrol.android.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.BuildConfig
import com.junbingao.remotecontrol.android.design.EmptyStateView
import com.junbingao.remotecontrol.android.design.FieldLabel
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.gallery.GalleryIndex
import com.junbingao.remotecontrol.android.gallery.GalleryPage
import com.junbingao.remotecontrol.android.navigation.HidesTabBar
import com.junbingao.remotecontrol.android.navigation.LocalNavigator
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.android.system.RowStyle

/**
 * What the shell draws for a route until the screens arrive (stage 3): each tab under its title
 * with the iPhone's own empty state, the gallery behind a debug-only row on Settings, and a
 * conversation link's own words.
 */
@Composable
fun ShellDestination(route: Any) {
    when (route) {
        is TabRoot -> TabPlaceholder(route.tab)
        is ConversationRoute -> ConversationPlaceholder(route)
        GalleryRoute -> GalleryIndex()
        is GalleryPageRoute -> GalleryPage(route.page)
        else -> Unit
    }
}

@Composable
private fun TabPlaceholder(tab: AppTab) {
    val navigator = LocalNavigator.current
    val title = when (tab) {
        AppTab.devices -> L10n.string("Devices")
        AppTab.sessions -> L10n.string("Sessions")
        AppTab.settings -> L10n.string("Settings")
    }
    NavigationScreen(title) { insets ->
        InsetGroupedList(Modifier.fillMaxSize(), contentPadding = insets.padding()) {
            if (tab == AppTab.settings && BuildConfig.DEBUG) {
                section(key = "debug", header = { FieldLabel("Diagnostics") }) {
                    row(key = "gallery", onClick = { navigator?.push(GalleryRoute) }, tag = "shell.gallery") {
                        Text("Primitive gallery", style = Theme.Text.label, color = Theme.ink)
                    }
                }
            }
            // The iPhone's own empty states, which is what each list shows before it has rows.
            when (tab) {
                AppTab.devices -> item(key = "empty") {
                    EmptyStateView(
                        "desktopcomputer",
                        L10n.string("No devices yet"),
                        L10n.string("Run one command on the machine where your agents live. It dials out to the gateway; nothing is exposed on the host."),
                    )
                }
                AppTab.sessions -> item(key = "empty") {
                    EmptyStateView(
                        "bubble.left.and.text.bubble.right",
                        L10n.string("No sessions yet"),
                        L10n.string("Add a device first, then start a session on it."),
                    )
                }
                AppTab.settings -> Unit
            }
        }
    }
}

@Composable
private fun ConversationPlaceholder(route: ConversationRoute) {
    HidesTabBar()
    NavigationScreen(route.link.sessionID, displayMode = TitleDisplayMode.inline) { insets ->
        Text(
            "${route.link.deviceID} · ${route.link.sessionID}",
            Modifier.padding(insets.padding()).padding(Theme.Space.page),
            style = SystemFont.body,
            color = Theme.inkSecondary,
        )
    }
}
