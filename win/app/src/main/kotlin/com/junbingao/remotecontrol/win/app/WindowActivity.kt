package com.junbingao.remotecontrol.win.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.FrameWindowScope
import java.awt.Frame
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * Whether a conversation shown in the window is in front of the person: the window is on screen,
 * not minimised, and the one Windows has given the keyboard — the Mac's `WindowChrome` keeps the
 * same fact from AppKit's window notifications, for the model's `isWindowActive`.
 */
@Composable
internal fun FrameWindowScope.WindowActivity(model: WinAppModel) {
    DisposableEffect(window) {
        val frame = window
        fun note() {
            model.isWindowActive = frame.isVisible && frame.isActive && (frame.extendedState and Frame.ICONIFIED) == 0
        }
        val events = object : WindowAdapter() {
            override fun windowActivated(event: WindowEvent) = note()
            override fun windowDeactivated(event: WindowEvent) = note()
            override fun windowIconified(event: WindowEvent) = note()
            override fun windowDeiconified(event: WindowEvent) = note()
            override fun windowStateChanged(event: WindowEvent) = note()
        }
        val visibility = object : ComponentAdapter() {
            override fun componentShown(event: ComponentEvent) = note()
            override fun componentHidden(event: ComponentEvent) = note()
        }
        frame.addWindowListener(events)
        frame.addWindowStateListener(events)
        frame.addComponentListener(visibility)
        note()
        onDispose {
            frame.removeWindowListener(events)
            frame.removeWindowStateListener(events)
            frame.removeComponentListener(visibility)
            model.isWindowActive = false
        }
    }
}
