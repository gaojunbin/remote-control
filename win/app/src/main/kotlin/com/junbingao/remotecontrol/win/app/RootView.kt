package com.junbingao.remotecontrol.win.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.win.chat.ChatPage
import com.junbingao.remotecontrol.win.design.WebBase
import com.junbingao.remotecontrol.win.design.overlay.OverlayHost
import com.junbingao.remotecontrol.win.design.overlay.OverlayRegistry
import com.junbingao.remotecontrol.win.devices.DevicePage
import com.junbingao.remotecontrol.win.devices.DevicesPage
import com.junbingao.remotecontrol.win.layout.AppLayout
import com.junbingao.remotecontrol.win.layout.BootView
import com.junbingao.remotecontrol.win.layout.Landing
import com.junbingao.remotecontrol.win.login.LoginPage
import com.junbingao.remotecontrol.win.sessions.SessionsPage
import com.junbingao.remotecontrol.win.settings.SettingsPage
import com.junbingao.remotecontrol.win.terminal.TerminalPage
import com.junbingao.remotecontrol.win.update.UpdateRequiredPage
import com.junbingao.remotecontrol.win.users.UsersPage

/**
 * What `web/src/App.tsx` renders: Update required above everything (A46), the canvas while the
 * app decides, the login page whenever nobody is signed in, and otherwise the route — inside the
 * topbar layout, or over the whole window for the conversation and the terminal. The overlay
 * layer covers all of it, and the web's breakpoints are read from the window's size here, once.
 *
 * `showing` is one view drawn where a screen goes, with everything the root gives a screen — the
 * breakpoints, the overlay layer, the base type and ink — as the Mac's `RootView(showing:)`; the
 * renderer uses it for a scenario about a single view, and it needs no model. Without it the
 * model is read from `LocalAppModel` (`WithAppModel`).
 */
@Composable
fun RootView(overlays: OverlayRegistry = remember { OverlayRegistry() }, showing: (@Composable () -> Unit)? = null) {
    WebBase(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalLayoutClass provides LayoutClass(maxWidth.value, maxHeight.value)) {
                OverlayHost(overlays) { if (showing != null) showing() else Screen() }
            }
        }
    }
}

/** The model as every screen reads it: the app's own, and the shell's part of it. */
@Composable
fun WithAppModel(model: WinAppModel, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppModel provides model, LocalShellState provides model, content = content)
}

@Composable
private fun Screen() {
    val model = LocalAppModel.current
    val requirement = model.connection.updateRequired
    when {
        requirement != null -> UpdateRequiredPage(requirement)
        model.isResuming -> BootView()
        // Signed out, every path is the login page. Drawing the requested page first and moving
        // from it would run its first requests on a connection that can only refuse them.
        !model.isSignedIn -> LoginPage()
        else -> RoutedScreen(model.router.route)
    }
}

/** One route's screen. The topbar routes share one layout, so moving between them keeps the topbar where it is, as the web's nested routes do. */
@Composable
private fun RoutedScreen(route: Route) {
    if (route.isInLayout) {
        AppLayout { LayoutPage(route) }
        return
    }
    when (route) {
        is Route.Chat -> ChatPage(deviceId = route.deviceId, sessionId = route.sessionId)
        is Route.Terminal -> TerminalPage(deviceId = route.deviceId)
        else -> Landing()
    }
}

/** The page a topbar route draws under the topbar. */
@Composable
private fun LayoutPage(route: Route) {
    when (route) {
        Route.Devices -> DevicesPage()
        is Route.Device -> DevicePage(deviceId = route.id)
        Route.Sessions -> SessionsPage()
        Route.Settings -> SettingsPage()
        Route.Users -> UsersPage()
        else -> {}
    }
}
