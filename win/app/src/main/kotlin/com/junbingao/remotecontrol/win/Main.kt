package com.junbingao.remotecontrol.win

import androidx.compose.ui.window.application
import com.junbingao.remotecontrol.win.app.LaunchOptions
import com.junbingao.remotecontrol.win.app.MainWindow
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource

/**
 * The Windows app: one window and the icon in the notification area. Closing the window leaves
 * the app running there, and Quit from the icon's menu ends it (`docs/DESIGN.md` § "The Windows
 * app" → **The window is Windows'**).
 */
fun main(arguments: Array<String>) {
    val options = LaunchOptions(arguments.toList())
    options.language?.let { InterfaceLanguageSource.current = it }
    application(exitProcessOnExit = true) {
        MainWindow(options)
    }
}
