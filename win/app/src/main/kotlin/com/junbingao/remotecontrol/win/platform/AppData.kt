package com.junbingao.remotecontrol.win.platform

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The app's own data folder: `%LOCALAPPDATA%\Remote Control`, which stays on this machine and out
 * of a roaming profile, as a token must.
 */
object AppData {
    val directory: Path by lazy {
        val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: Paths.get(System.getProperty("user.home"), "AppData", "Local").toString()
        Paths.get(local, "Remote Control")
    }

    /** Where the vault keeps its sealed files. */
    val secrets: Path get() = directory.resolve("secrets")
}
