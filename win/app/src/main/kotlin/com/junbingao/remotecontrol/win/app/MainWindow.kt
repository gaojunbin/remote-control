package com.junbingao.remotecontrol.win.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.overlay.OverlayRegistry
import com.junbingao.remotecontrol.win.platform.AppIcon
import com.junbingao.remotecontrol.win.platform.AppTray
import com.junbingao.remotecontrol.win.platform.TrayToasts
import com.junbingao.remotecontrol.win.platform.ReduceMotion
import com.junbingao.remotecontrol.win.platform.WindowsBadgeSurface
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import com.junbingao.remotecontrol.win.unseen.SystemTaskbarBadge
import com.junbingao.remotecontrol.win.unseen.UnseenFeature
import java.awt.Dimension

/**
 * The app's one window (`docs/DESIGN.md` § "The Windows app" → **The window is Windows'**): the
 * system title bar, so snapping, resizing and the caption buttons are Windows' own; 1280 × 860 at
 * first and never smaller than 480 × 560; always light. Closing it leaves the app running and
 * connected in the notification area, whose icon opens it again and offers Quit; where there is no
 * notification area, closing it quits, and quitting takes an ephemeral run's files with it.
 * Escape closes the newest overlay, and the rest of the keyboard map is `AppCommands`, with the
 * mouse's back and forward buttons. The app's icon carries the number of sessions with a red dot
 * (A47): on the taskbar button while the window is open, in the notification area while it is
 * closed.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ApplicationScope.MainWindow(model: WinAppModel) {
    var visible by remember { mutableStateOf(true) }
    val overlays = remember { OverlayRegistry() }
    // Nothing but the form, or the Update required screen, is reachable without an account the gateway accepts.
    val commands = remember { AppCommands(model.router, canNavigate = { model.isSignedIn && model.connection.updateRequired == null }) }
    val tray = remember { AppTray.install() }
    // A47: the number on the app's icon, drawn by Windows once there is a window to lay it over.
    val badgeSurface = remember(tray) { WindowsBadgeSurface(tray) }
    val taskbar = remember(badgeSurface) { SystemTaskbarBadge(badgeSurface) }
    var frame by remember { mutableStateOf<java.awt.Window?>(null) }
    val quit = {
        model.discardEphemeralState()
        exitApplication()
    }
    DisposableEffect(tray) {
        model.toasts = TrayToasts.make(tray)
        tray?.onOpen = { target ->
            visible = true
            if (target != null) model.toasts.onOpen?.invoke(target)
        }
        tray?.onQuit = quit
        onDispose { tray?.remove() }
    }
    LaunchedEffect(tray) {
        snapshotFlow { InterfaceLanguageSource.current }.collect { tray?.relabel() }
    }
    LaunchedEffect(taskbar) {
        UnseenFeature.state(of = model).badge.platform = taskbar
        snapshotFlow { visible to frame }.collect { (shown, window) ->
            badgeSurface.window = window
            taskbar.windowShown(shown && window != null)
        }
    }
    LaunchedEffect(model) { model.restoreOrPrompt() }
    Window(
        onCloseRequest = { if (tray != null) visible = false else quit() },
        visible = visible,
        state = rememberWindowState(size = InitialWindow.size(), position = WindowPosition(Alignment.Center)),
        title = S.productName,
        icon = AppIcon.painter,
        onPreviewKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && overlays.dismissNewest()) true else commands.handle(event)
        },
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(InitialWindow.minimum.width.value.toInt(), InitialWindow.minimum.height.value.toInt())
            window.background = java.awt.Color(0xF5, 0xF5, 0xF4)
            model.showWindow = {
                visible = true
                window.toFront()
                window.requestFocus()
            }
            frame = window
        }
        WindowActivity(model)
        CompositionLocalProvider(LocalReduceMotion provides ReduceMotion.current) {
            WithAppModel(model) {
                Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { commands.handle(it.button) }) {
                    RootView(overlays)
                }
            }
        }
    }
}
