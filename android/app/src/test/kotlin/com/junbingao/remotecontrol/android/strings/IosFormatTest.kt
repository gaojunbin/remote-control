package com.junbingao.remotecontrol.android.strings

import org.junit.Assert.assertEquals
import org.junit.Test

/** `String(format:)` as Foundation reads a catalogue value, case by case. */
class IosFormatTest {
    private fun format(template: String, vararg arguments: Any?) = IosFormat.format(template, arguments.toList())

    @Test
    fun objectsAndIntegersTakeTheNextArgument() {
        assertEquals("Delete alice and its 1 device?", format("Delete %@ and its %lld device?", "alice", 1))
        assertEquals("3 messages", format("%lld messages", 3L))
        assertEquals("-7 and 42", format("%d and %ld", -7, 42L))
    }

    @Test
    fun positionalSpecifiersNameTheirArgumentWhateverCameBefore() {
        assertEquals("mac-studio 上的 Claude Code", format("%2\$@ 上的 %1\$@", "Claude Code", "mac-studio"))
        assertEquals("b a b", format("%2\$@ %1\$@ %2\$@", "a", "b"))
    }

    @Test
    fun decimalsAreSetWithAPointInEveryLanguage() {
        assertEquals("48.2k", format("%.1fk", 48.2))
        assertEquals("1.50", format("%.2f", 1.5))
        assertEquals("2", format("%.0f", 2.0))
        assertEquals("3.140000", format("%f", 3.14))
    }

    @Test
    fun aPercentSignIsWrittenAsTwo() {
        assertEquals("100%", format("%lld%%", 100))
        assertEquals("% done", format("%% done"))
    }

    @Test
    fun widthsPadAsPrintfDoes() {
        assertEquals("07", format("%02d", 7))
        assertEquals("  7", format("%3d", 7))
        assertEquals("7  |", format("%-3d|", 7))
        assertEquals("-07", format("%03d", -7))
    }

    @Test
    fun aSpecifierWithNoArgumentLeftPrintsNothingRatherThanThrowing() {
        assertEquals("a and ", format("%@ and %@", "a"))
        assertEquals("only ", format("only %lld"))
    }

    @Test
    fun textWithoutSpecifiersIsLeftAlone() {
        assertEquals("Sign in", format("Sign in", "unused"))
        assertEquals("50% off", format("50%% off"))
        assertEquals("a percent sign with nothing after it stays", "ends with %", format("ends with %"))
    }

    @Test
    fun otherConversionsFollowC() {
        assertEquals("ff FF 17", format("%x %X %o", 255, 255, 15))
        assertEquals("A", format("%c", 65))
        assertEquals("text", format("%s", "text"))
        assertEquals("(null)", format("%@", null))
    }
}
