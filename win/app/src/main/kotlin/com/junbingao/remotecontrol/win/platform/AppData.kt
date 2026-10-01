package com.junbingao.remotecontrol.win.platform

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The app's own data folder: `%LOCALAPPDATA%\Remote Control`, which stays on this machine and out
 * of a roaming profile, as a token must. It holds what the Mac keeps in the Keychain, the standard
 * defaults and Application Support: the sealed token, the preferences, the cached lists and
 * transcripts, and the composer's drafts.
 */
object AppData {
    val directory: Path by lazy {
        val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: Paths.get(System.getProperty("user.home"), "AppData", "Local").toString()
        Paths.get(local, "Remote Control")
    }

    /** Where the vault keeps its sealed files. */
    val secrets: Path get() = directory.resolve("secrets")

    /** The preferences, the core's `UserDefaults` (`FileUserDefaults`). */
    val defaults: Path get() = directory.resolve("defaults.json")

    /** The core's `LocalCache`: the account's devices, sessions and transcripts. */
    val cache: Path get() = directory.resolve("Cache")

    /** The core's `DraftStore`: what the composer held, per account and session. */
    val drafts: Path get() = directory.resolve("Drafts")
}
