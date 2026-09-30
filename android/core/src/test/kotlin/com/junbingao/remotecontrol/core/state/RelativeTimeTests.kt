package com.junbingao.remotecontrol.core.state

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `RelativeTime`, which RCCore exercises only through the stores' suites: the words and numbers
 * it writes, each the one RCCore's `String(format:)` writes — a fraction exactly between two
 * printable values goes to the even one.
 */
class RelativeTimeTests {
    private val now = Instant.ofEpochSecond(1_788_955_200)
    private fun ago(seconds: Long): Long = (now.epochSecond - seconds) * 1000

    /** A list row's age: now, minutes, hours, yesterday, days — and nothing for no time at all. */
    @Test
    fun short() {
        assertEquals("", RelativeTime.short(since = 0, now = now))
        assertEquals("now", RelativeTime.short(since = ago(44), now = now))
        assertEquals("0m", RelativeTime.short(since = ago(45), now = now))
        assertEquals("59m", RelativeTime.short(since = ago(3599), now = now))
        assertEquals("1h", RelativeTime.short(since = ago(3600), now = now))
        assertEquals("23h", RelativeTime.short(since = ago(86_399), now = now))
        assertEquals("yesterday", RelativeTime.short(since = ago(86_400), now = now))
        assertEquals("2d", RelativeTime.short(since = ago(172_800), now = now))
    }

    /** A tool call's length: tenths under ten seconds, whole seconds under a minute, then minutes and seconds. */
    @Test
    fun duration() {
        assertEquals("6.4s", RelativeTime.duration(milliseconds = 6400))
        assertEquals("6.2s", RelativeTime.duration(milliseconds = 6250))
        assertEquals("0.0s", RelativeTime.duration(milliseconds = 0))
        assertEquals("11s", RelativeTime.duration(milliseconds = 10_500))
        assertEquals("59s", RelativeTime.duration(milliseconds = 59_499))
        assertEquals("1m 12s", RelativeTime.duration(milliseconds = 72_900))
    }

    /** A token count in thousands and millions. */
    @Test
    fun compactCount() {
        assertEquals("999", RelativeTime.compactCount(999))
        assertEquals("48.2k", RelativeTime.compactCount(48_250))
        assertEquals("1000.0k", RelativeTime.compactCount(999_999))
        assertEquals("1.5M", RelativeTime.compactCount(1_450_001))
    }

    /** A pairing code's time left, which never runs below zero. */
    @Test
    fun countdown() {
        assertEquals("9:47", RelativeTime.countdown(to = (now.epochSecond + 587) * 1000, now = now))
        assertEquals("0:00", RelativeTime.countdown(to = (now.epochSecond - 5) * 1000, now = now))
    }

    /** The same words in Chinese. */
    @Test
    fun chinese() {
        L10n.use(InterfaceLanguage.zhHans)
        try {
            assertEquals("刚刚", RelativeTime.short(since = ago(10), now = now))
            assertEquals("5 分钟前", RelativeTime.short(since = ago(300), now = now))
            assertEquals("6.4 秒", RelativeTime.duration(milliseconds = 6400))
            assertEquals("1 分 12 秒", RelativeTime.duration(milliseconds = 72_900))
        } finally {
            L10n.use(InterfaceLanguage.en)
        }
    }
}
