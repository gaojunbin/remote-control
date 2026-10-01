package com.junbingao.remotecontrol.win.app

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/**
 * What the launch arguments ask of this run, as the Mac app reads its own.
 *
 * - `--demo`: the offline demo gateway around the app, as the iPhone app's.
 * - `--demo-account`: the offline demo behind the sign-in form instead, so the account screens can
 *   be driven with no gateway (`--registration-open` opens its registrations).
 * - `--demo-update-required`: the demo claims a minimum above this build, which is how the
 *   blocking Update required screen is reached (A46).
 * - `--ephemeral`: nothing of the person's is read or written — the token in memory, preferences
 *   in memory, caches and drafts in a scratch directory, all removed on quit. Every automated run
 *   uses it.
 * - `--reset-state`: start as a fresh install.
 * - `--language=en|zh-Hans`: the interface language for the whole run.
 */
data class LaunchOptions(
    val demo: Boolean = false,
    val demoAccount: Boolean = false,
    val registrationOpen: Boolean = false,
    val demoUpdateRequired: Boolean = false,
    val ephemeral: Boolean = false,
    val resetState: Boolean = false,
    val language: InterfaceLanguage? = null,
) {
    constructor(arguments: List<String>) : this(
        demo = "--demo" in arguments,
        demoAccount = "--demo-account" in arguments,
        registrationOpen = "--registration-open" in arguments,
        demoUpdateRequired = "--demo-update-required" in arguments,
        ephemeral = "--ephemeral" in arguments,
        resetState = "--reset-state" in arguments,
        language = arguments.firstOrNull { it.startsWith("--language=") }
            ?.let { InterfaceLanguage(rawValue = it.removePrefix("--language=")) },
    )
}
