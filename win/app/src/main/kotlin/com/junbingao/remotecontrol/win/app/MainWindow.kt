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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.overlay.OverlayRegistry
import com.junbingao.remotecontrol.win.gallery.Gallery
import com.junbingao.remotecontrol.win.platform.AppIcon
import com.junbingao.remotecontrol.win.platform.AppTray
import com.junbingao.remotecontrol.win.platform.ReduceMotion
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import java.awt.Dimension

/**
 * The app's one window (`docs/DESIGN.md` § "The Windows app" → **The window is Windows'**): the
 * system title bar, so snapping, resizing and the caption buttons are Windows' own; 1280 × 860 at
 * first and never narrower than 480; always light. Closing it leaves the app running in the
 * notification area, whose icon opens it again and offers Quit; where there is no notification
 * area, closing it quits. Escape closes the newest overlay, and the rest of the keyboard map is
 * `AppCommands`, with the mouse's back and forward buttons.
 *
 * The screens arrive with the app model (stage 2) and the features (stage 3); until then the
 * window shows the design system's gallery.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ApplicationScope.MainWindow(options: LaunchOptions) {
    var visible by remember { mutableStateOf(true) }
    val router = remember { Router() }
    val overlays = remember { OverlayRegistry() }
    // Nothing is reachable before the model signs someone in (stage 2).
    val commands = remember { AppCommands(router, canNavigate = { false }) }
    val tray = remember { AppTray.install() }
    val services = remember { AppServices.make(options, tray) }
    DisposableEffect(tray) {
        tray?.onOpen = { target ->
            visible = true
            if (target != null) services.toasts.onOpen?.invoke(target)
        }
        tray?.onQuit = ::exitApplication
        onDispose { tray?.remove() }
    }
    LaunchedEffect(tray) {
        snapshotFlow { InterfaceLanguageSource.current }.collect { tray?.relabel() }
    }
    Window(
        onCloseRequest = { if (tray != null) visible = false else exitApplication() },
        visible = visible,
        state = rememberWindowState(size = DpSize(1280.dp, 860.dp), position = WindowPosition(Alignment.Center)),
        title = S.productName,
        icon = AppIcon.painter,
        onPreviewKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && overlays.dismissNewest()) true else commands.handle(event)
        },
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(480, 560)
            window.background = java.awt.Color(0xF5, 0xF5, 0xF4)
        }
        CompositionLocalProvider(LocalReduceMotion provides ReduceMotion.current) {
            Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { commands.handle(it.button) }) {
                RootView(overlays) { Gallery() }
            }
        }
    }
}
