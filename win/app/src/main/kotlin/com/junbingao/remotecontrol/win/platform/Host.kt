package com.junbingao.remotecontrol.win.platform

/** Which operating system the app is running on: the services that are Windows' own ask. */
object Host {
    private val name = System.getProperty("os.name").orEmpty()

    val isWindows: Boolean = name.startsWith("Windows")
    val isMac: Boolean = name.startsWith("Mac")
}
