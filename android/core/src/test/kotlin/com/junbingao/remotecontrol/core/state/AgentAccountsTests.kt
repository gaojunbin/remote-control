package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AccountMethod
import com.junbingao.remotecontrol.core.protocol.AgentAccount
import com.junbingao.remotecontrol.core.protocol.AgentLimit
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Amendment A33: the words the page writes about how an agent is signed in, and what is left of its
 * quota. The wire half of RCCore's suite of this name is in `protocol/AgentAccountsTests.kt`;
 * `demoShapes`, which reads the demo's machines, is in `demo/AgentAccountsTests.kt`.
 */
class AgentAccountsTests {
    // The line that says how it is signed in

    /** The three vendors the app has a name for, and one it has not. */
    @Test
    fun vendorNames() {
        for ((provider, name) in listOf("anthropic" to "Anthropic", "openai" to "OpenAI", "xai" to "xAI",
                                        "mistral" to "mistral")) {
            assertEquals(name, AccountLine.vendorName(provider), provider)
        }
    }

    /** An account reads vendor, plan, tier and email, each only when reported. */
    @Test
    fun accountLine() {
        val full = AgentAccount(provider = "anthropic", method = AccountMethod.account, plan = "max", tier = "Max 5x",
                                email = "me@example.com")
        // The plan is raised at its first letter; the tier is the device's own words and is printed
        // exactly as it arrived.
        assertEquals("Anthropic account · Max · Max 5x · me@example.com", AccountLine.text(full))

        val plain = AgentAccount(provider = "xai", method = AccountMethod.account, plan = null, email = "me@example.com")
        assertEquals("xAI account · me@example.com", AccountLine.text(plain))

        val bare = AgentAccount(provider = "openai", method = AccountMethod.account)
        assertEquals("OpenAI account", AccountLine.text(bare))
    }

    /** A key says so, and names a third-party host when there is one. */
    @Test
    fun keyLine() {
        assertEquals("Anthropic API key", AccountLine.text(AgentAccount(provider = "anthropic", method = AccountMethod.apiKey)))
        assertEquals("OpenAI API key · api.relay.example",
                     AccountLine.text(AgentAccount(provider = "openai", method = AccountMethod.apiKey,
                                                   endpoint = "api.relay.example")))
        assertEquals("Not signed in", AccountLine.notSignedIn)
    }

    // The meter

    /** A window is named by the unit it divides into. */
    @Test
    fun windowNames() {
        for ((minutes, name) in listOf(300 to "5-hour", 1440 to "24-hour", 10080 to "7-day", 2880 to "2-day",
                                       60 to "1-hour", 90 to "90-minute")) {
            assertEquals(name, QuotaWindow.length(minutes = minutes), "$minutes minutes")
        }
    }

    /** And carries its scope after it where the vendor confined it to one. */
    @Test
    fun scopedWindow() {
        val weekly = AgentLimit(windowMinutes = 10080, usedPercent = 64.0)
        assertEquals("7-day", QuotaWindow.name(weekly))
        val scoped = AgentLimit(windowMinutes = 10080, scope = "Fable", usedPercent = 64.0)
        assertEquals("7-day · Fable", QuotaWindow.name(scoped))
    }

    /**
     * The bands turn where the design says they turn. The page's only colour rule: ink, then the
     * warning colour past 80, then the danger colour at 100.
     */
    @Test
    fun bands() {
        for ((percent, band) in listOf(0.0 to QuotaWindow.Band.normal, 80.0 to QuotaWindow.Band.normal,
                                       80.5 to QuotaWindow.Band.warning, 99.9 to QuotaWindow.Band.warning,
                                       100.0 to QuotaWindow.Band.danger, 140.0 to QuotaWindow.Band.danger)) {
            assertEquals(band, QuotaWindow.band(usedPercent = percent), "$percent%")
        }
    }

    /** A bar draws what it can and no more. */
    @Test
    fun fills() {
        assertEquals(0.0, QuotaWindow.fill(usedPercent = 0.0))
        assertEquals(0.5, QuotaWindow.fill(usedPercent = 50.0))
        assertEquals(1.0, QuotaWindow.fill(usedPercent = 140.0))
        assertEquals(0.0, QuotaWindow.fill(usedPercent = -10.0))
        assertEquals("16%", QuotaWindow.percentage(16.4))
        assertEquals("100%", QuotaWindow.percentage(99.6))
        assertEquals("100%", QuotaWindow.percentage(120.0))
    }

    /** A window that comes back today reads as a clock, and any other as a day and a clock. */
    @Test
    fun resetWording() {
        val zone = ZoneOffset.UTC
        val locale = Locale.forLanguageTag("en-GB")
        // 2026-09-15 is a Tuesday.
        val now = Instant.ofEpochSecond(1_789_470_000)   // 2026-09-15 11:00 UTC
        val later = 1_789_486_800_000L                    // the same day, 15:40 UTC
        val nextWeek = 1_790_114_400_000L                 // 2026-09-22 22:00 UTC, a Tuesday

        assertEquals("15:40", QuotaWindow.clock(at = later, now = now, zone = zone, locale = locale))
        assertEquals("Tue 22:00", QuotaWindow.clock(at = nextWeek, now = now, zone = zone, locale = locale))
        assertEquals("resets 15:40", QuotaWindow.resets(at = later, now = now, zone = zone, locale = locale))
    }
}
