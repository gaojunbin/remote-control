package com.junbingao.remotecontrol.win

import androidx.compose.ui.window.application
import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.win.app.LaunchOptions
import com.junbingao.remotecontrol.win.app.MainWindow
import com.junbingao.remotecontrol.win.app.WinAppModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The Windows app: one window and the icon in the notification area. Closing the window leaves
 * the app running there, and Quit from the icon's menu ends it (`docs/DESIGN.md` § "The Windows
 * app" → **The window is Windows'**).
 */
fun main(arguments: Array<String>) {
    // A46: the version this build states, once, before anything reads it — the packaged one, which
    // the installer's launcher sets, and the core's own in a run from the build.
    AppBuild.version = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: AppBuild.shipped
    val model = WinAppModel(options = LaunchOptions(arguments.toList()), tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main))
    // A process ended from outside skips the Quit; an ephemeral run still takes its files with it.
    Runtime.getRuntime().addShutdownHook(Thread { model.discardEphemeralState() })
    application(exitProcessOnExit = true) {
        MainWindow(model)
    }
}
