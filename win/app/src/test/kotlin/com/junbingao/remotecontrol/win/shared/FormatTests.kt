package com.junbingao.remotecontrol.win.shared

import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `web/tests/format.test.ts`, case for case, in English: the Mac's `FormatTests`. */
class FormatTests {
    private val now = 1_700_000_000_000L

    private fun ago(ms: Long) = now - ms

    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    @Test
    fun compactScaleOfTheSessionRows() {
        assertEquals("now", Format.relativeTime(ago(1_000), now = now))
        assertEquals("4m", Format.relativeTime(ago(4 * 60_000), now = now))
        assertEquals("3h", Format.relativeTime(ago(3 * 3_600_000), now = now))
        assertEquals("2d", Format.relativeTime(ago(2 * 86_400_000), now = now))
    }

    @Test
    fun longerScaleOfRecentDirectories() {
        assertEquals("just now", Format.relativeAgo(ago(30_000), now = now))
        assertEquals("2h ago", Format.relativeAgo(ago(2 * 3_600_000), now = now))
        assertEquals("yesterday", Format.relativeAgo(ago(30 * 3_600_000), now = now))
    }

    /** `toLocaleDateString(dateLocale, { month: 'short', day: 'numeric' })`, in the local time zone as a browser writes it. */
    @Test
    fun aDateOlderThanAWeekIsWrittenAsADate() {
        val old = Format.relativeTime(ago(30 * 86_400_000L), now = now)
        assertTrue(Regex("^(Oct|Nov) [0-9]{1,2}$").matches(old), old)
        InterfaceLanguageSource.current = InterfaceLanguage.zhHans
        val chinese = Format.relativeTime(ago(30 * 86_400_000L), now = now)
        assertTrue(Regex("^1[01]月[0-9]{1,2}日$").matches(chinese), chinese)
        assertEquals("3 分钟前", Format.relativeAgo(ago(3 * 60_000), now = now))
    }

    @Test
    fun toolDurations() {
        assertEquals("820ms", Format.duration(820.0))
        assertEquals("6.4s", Format.duration(6_400.0))
        assertEquals("38s", Format.duration(38_020.0))
        assertEquals("1m 12s", Format.duration(72_000.0))
        assertEquals("3h 5m", Format.duration(3 * 3_600_000.0 + 5 * 60_000))
    }

    @Test
    fun countdownClocks() {
        assertEquals("0:12", Format.clock(12_000.0))
        assertEquals("9:47", Format.clock(587_000.0))
        assertEquals("0:00", Format.clock(-5.0))
    }

    @Test
    fun tokenCountsAndSizes() {
        assertEquals("940", Format.compactNumber(940.0))
        assertEquals("48.2k", Format.compactNumber(48_200.0))
        assertEquals("203k", Format.compactNumber(203_000.0))
        assertEquals("1.5M", Format.compactNumber(1_500_000.0))
        assertEquals("512 B", Format.bytes(512))
        assertEquals("6.0 MiB", Format.bytes(6 * 1024 * 1024))
    }

    @Test
    fun pathsAndLatency() {
        assertEquals("~/dev/gateway", Format.tildePath("/Users/me/dev/gateway"))
        assertEquals("~/work/api", Format.tildePath("/home/ci/work/api"))
        assertEquals("/opt/tools", Format.tildePath("/opt/tools"))
        assertEquals("~/dev", Format.tildePath("/Users/me/dev", home = "/Users/me"))
        assertEquals("gateway", Format.baseName("/Users/me/dev/gateway"))
        assertEquals("gateway", Format.baseName("/Users/me/dev/gateway/"))
        assertEquals("/", Format.baseName("/"))
        assertEquals("18 ms", Format.latency(18.0))
        assertEquals("—", Format.latency(null))
    }

    @Test
    fun foldLines() {
        val short = Format.foldLines("a\nb\nc", maxLines = 20)
        assertTrue(!short.folded && short.head == "a\nb\nc" && short.total == 3)
        val text = (0 until 50).joinToString("\n") { "line $it" }
        val long = Format.foldLines(text, maxLines = 20)
        assertTrue(long.folded && long.total == 50)
        assertEquals(20, long.head.split("\n").size)
    }

    @Test
    fun javaScriptRounding() {
        assertEquals("0.3", Format.toFixed(0.25, 1))
        assertEquals(3.0, Format.jsRound(2.5))
        assertEquals(-2.0, Format.jsRound(-2.5))
    }
}
