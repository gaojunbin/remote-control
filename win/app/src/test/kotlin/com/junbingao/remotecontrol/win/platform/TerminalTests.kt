package com.junbingao.remotecontrol.win.platform

import com.jediterm.core.util.TermSize
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The terminal's wire, without a window: what the emulator reads, what it sends, and its theme. */
class TerminalTests {
    private fun read(connector: FeedConnector, count: Int): String {
        val buffer = CharArray(64)
        val read = connector.read(buffer, 0, count)
        return String(buffer, 0, read)
    }

    @Test
    fun theDevicesBytesAreReadAsUtf8EvenSplitAcrossChunks() {
        val connector = FeedConnector()
        val bytes = "a中b".toByteArray()
        connector.feed(bytes.copyOfRange(0, 2))
        connector.feed(bytes.copyOfRange(2, bytes.size))
        var text = ""
        while (text.length < 3) text += read(connector, 64)
        assertEquals("a中b", text)
    }

    @Test
    fun typingAndResizingAreHandedOn() {
        val connector = FeedConnector()
        val typed = mutableListOf<ByteArray>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        connector.onInput = { typed += it }
        connector.onSize = { cols, rows -> sizes += cols to rows }
        connector.write("ls\r")
        connector.write(ByteArray(0))
        connector.resize(TermSize(120, 40))
        connector.resize(TermSize(0, 0))
        assertEquals(1, typed.size)
        assertContentEquals("ls\r".toByteArray(), typed[0])
        assertEquals(listOf(120 to 40), sizes)
        connector.close()
        assertEquals(-1, connector.read(CharArray(4), 0, 4))
    }

    @Test
    fun theThemeIsTheWebs() {
        assertEquals(16, TerminalTheme.ansi.size)
        assertEquals(0xFAFAF9, TerminalTheme.background)
        assertEquals(5_000, TerminalTheme.settings.bufferMaxLinesCount)
        assertTrue(!TerminalTheme.settings.audibleBell())
        assertContentEquals(byteArrayOf(0x1B, 0x63), TerminalFeed.fullReset)
    }
}
