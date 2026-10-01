package com.junbingao.remotecontrol.win.preview

import kotlin.system.exitProcess

// The renderer every agent verifies with: the app's own views in an offscreen scene of the
// window's size, written as PNG. It never shows a window, a taskbar entry or a notification-area
// icon: it runs headless (`java.awt.headless`, set by the build).
fun main(arguments: Array<String>) {
    exitProcess(PreviewCommand.run(arguments.toList()))
}
