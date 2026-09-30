package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.protocol.AppSupport
import com.junbingao.remotecontrol.core.protocol.AppsInfo
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.TurnStartedPayload
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf
import com.junbingao.remotecontrol.core.transport.HealthResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// RCCore's `PolishTests.swift` holds four suites. The two about dictation polish itself drive
// `DictationSpan`, `DictationPolish` and `ChatStore`, and are `core-state`'s to add here; so are
// `connectionRule` below (`ConnectionStore`) and `demo` (`DemoFixtures`).

/** Amendment A31 — the version comparison, and the rule built on it. */
class AppVersionTests {
    /** Versions compare part by part, not as text. */
    @Test
    fun ordering() {
        assertTrue(AppVersion("1.2.3") < AppVersion("1.10.0"))
        assertTrue(AppVersion("0.9.0") < AppVersion("1.0.0"))
        assertTrue(AppVersion("2.0.0") > AppVersion("1.99.99"))
        assertEquals(AppVersion("1.2.0"), AppVersion("1.2"))
        assertEquals(AppVersion("0.0.0"), AppVersion(""))
        assertEquals(AppVersion("0.0.0"), AppVersion("not a version"))
    }

    /** Only a build below the minimum has to update. */
    @Test
    fun rule() {
        val apps = AppsInfo(ios = AppSupport(minimumVersion = "1.2.0", updateURL = "https://testflight.apple.com/join/X"))
        assertNotNull(AppUpdateRequirement.of(apps, current = "1.1.9"))
        assertNull(AppUpdateRequirement.of(apps, current = "1.2.0"))
        assertNull(AppUpdateRequirement.of(apps, current = "2.0.0"))
        assertNull(AppUpdateRequirement.of(null, current = "0.0.1"))
        assertNull(AppUpdateRequirement.of(AppsInfo(ios = null), current = "0.0.1"))
    }

    /** Only an https link is offered to the person. */
    @Test
    fun link() {
        val secure = AppsInfo(ios = AppSupport(minimumVersion = "9.0.0", updateURL = "https://apps.apple.com/app/id1"))
        assertNotNull(AppUpdateRequirement.of(secure, current = "1.0.0")?.updateURL)
        val other = AppsInfo(ios = AppSupport(minimumVersion = "9.0.0", updateURL = "itms://apps"))
        assertNull(AppUpdateRequirement.of(other, current = "1.0.0")?.updateURL)
        val none = AppsInfo(ios = AppSupport(minimumVersion = "9.0.0"))
        assertNull(AppUpdateRequirement.of(none, current = "1.0.0")?.updateURL)
    }

    /** Each app is held to its own entry (A45). */
    @Test
    fun ownEntry() {
        val both = AppsInfo(ios = AppSupport(minimumVersion = "1.0.0"), macos = AppSupport(minimumVersion = "2.0.0"))
        assertNotNull(AppUpdateRequirement.of(both, app = InstalledApp.macos, current = "1.5.0"))
        assertNull(AppUpdateRequirement.of(both, app = InstalledApp.ios, current = "1.5.0"))
        val iosOnly = AppsInfo(ios = AppSupport(minimumVersion = "9.0.0"))
        assertNull(AppUpdateRequirement.of(iosOnly, app = InstalledApp.macos, current = "1.0.0"))
    }

    /** The Mac app's entry decodes beside the iPhone app's (A45). */
    @Test
    fun decodesMacEntry() {
        val apps = jsonObjectOf("ios" to mapOf("minimum_version" to "0.1.0"),
                                "macos" to mapOf("minimum_version" to "1.11.0")).decode<AppsInfo>()
        assertEquals("0.1.0", apps.ios?.minimumVersion)
        assertEquals("1.11.0", apps.macos?.minimumVersion)
        assertEquals("1.11.0", apps.support(InstalledApp.macos)?.minimumVersion)
    }

    /** Each of the four apps is held to its own entry, and to no other (A46). */
    @Test
    fun fourEntries() {
        val apps = AppsInfo(ios = AppSupport(minimumVersion = "1.0.0"), macos = AppSupport(minimumVersion = "2.0.0"),
                            android = AppSupport(minimumVersion = "3.0.0", updateURL = "https://play.google.com/store"),
                            windows = AppSupport(minimumVersion = "4.0.0"))
        assertNotNull(AppUpdateRequirement.of(apps, app = InstalledApp.android, current = "2.5.0"))
        assertNull(AppUpdateRequirement.of(apps, app = InstalledApp.android, current = "3.0.0"))
        assertEquals("https://play.google.com/store",
                     AppUpdateRequirement.of(apps, app = InstalledApp.android, current = "2.5.0")?.updateURL?.toString())
        assertNotNull(AppUpdateRequirement.of(apps, app = InstalledApp.windows, current = "3.5.0"))
        assertNull(AppUpdateRequirement.of(apps, app = InstalledApp.windows, current = "4.0.1"))
        // A gateway older than A46 names neither, and asks nothing of either.
        val older = AppsInfo(ios = AppSupport(minimumVersion = "9.0.0"), macos = AppSupport(minimumVersion = "9.0.0"))
        assertNull(AppUpdateRequirement.of(older, app = InstalledApp.android, current = "1.0.0"))
        assertNull(AppUpdateRequirement.of(older, app = InstalledApp.windows, current = "1.0.0"))
    }

    /** The Android and Windows entries decode from the frozen health response (A46). */
    @Test
    fun decodesAndroidAndWindowsEntries() {
        val apps = assertNotNull(FixtureSource.json("http/health.response.json").decode<HealthResponse>().apps)
        assertEquals("1.12.0", apps.android?.minimumVersion)
        assertEquals("1.12.0", apps.windows?.minimumVersion)
        assertEquals("1.12.0", apps.support(InstalledApp.android)?.minimumVersion)
        assertEquals("1.12.0", apps.support(InstalledApp.windows)?.minimumVersion)
    }

    /** The version an app states at startup is the one the rule reads; until then it is the shipped one. */
    @Test
    fun statedVersion() {
        assertEquals(AppBuild.shipped, AppBuild.version)
        val apps = AppsInfo(android = AppSupport(minimumVersion = "1.12.0"))
        try {
            AppBuild.version = "1.11.0"
            assertEquals(AppVersion("1.11.0"), AppUpdateRequirement.of(apps, app = InstalledApp.android)?.current)
        } finally {
            AppBuild.version = AppBuild.shipped
        }
        assertNull(AppUpdateRequirement.of(apps, app = InstalledApp.android))
    }
}

/** Amendment A30 — words another agent put into a Claude conversation. */
class AgentMessageTests {
    /** `agent` decodes as itself and reads like a turn nobody here started. */
    @Test
    fun source() {
        val payload = jsonObjectOf("text" to "recon-ios: done", "source" to "agent").decode<UserMessagePayload>()
        assertEquals(EventSource.agent, payload.source)
        assertTrue(payload.source.isElsewhere)
        assertTrue(EventSource.terminal.isElsewhere)
        assertFalse(EventSource.remote.isElsewhere)
        assertFalse(EventSource.queue.isElsewhere)
    }

    /** A turn another agent's message started carries the trigger. */
    @Test
    fun trigger() {
        val payload = jsonObjectOf("turn_id" to "t", "trigger" to "agent").decode<TurnStartedPayload>()
        assertEquals(EventSource.agent, payload.trigger)
        assertTrue(payload.trigger.isElsewhere)
    }
}
