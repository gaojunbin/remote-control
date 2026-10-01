package com.junbingao.remotecontrol.core.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The diagnostic report names the app that wrote it. RCCore's is the iPhone's and the Mac's, and
 * its first line says iOS for both; here four apps share the store, so each says which it is
 * (orchestrator's ruling, round 56).
 */
class DiagnosticReportTests {
    private fun report(app: InstalledApp, platform: String): String =
        SettingsStore(defaults = MemoryUserDefaults()).diagnosticReport(
            app = app, appVersion = AppBuild.shipped, platform = platform, osVersion = "1", phase = ConnectionPhase.Connected,
            deviceCount = 2, sessionCount = 4, sttEnabled = true, isDemo = false,
        )

    /** The first line names the installed app, and only that one. */
    @Test
    fun namesTheInstalledApp() {
        val table = listOf(
            Triple(InstalledApp.ios, "iOS", "iPhone"),
            Triple(InstalledApp.macos, "macOS", "Mac"),
            Triple(InstalledApp.android, "Android", "Android 15"),
            Triple(InstalledApp.windows, "Windows", "Windows 11"),
        )
        for ((app, name, platform) in table) {
            assertEquals("Remote Control for $name — diagnostic snapshot", report(app, platform).lines().first(), app.rawValue)
        }
        assertFalse("iOS" in report(InstalledApp.android, "Android 15"), "an Android report never says iOS")
        assertFalse("iOS" in report(InstalledApp.windows, "Windows 11"), "and neither does a Windows one")
    }
}
