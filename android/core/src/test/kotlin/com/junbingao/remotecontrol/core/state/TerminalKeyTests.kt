package com.junbingao.remotecontrol.core.state

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amendment A38: the phone's key bar, checked as a table rather than through a view. Every cap sends
 * the byte sequence a terminal expects, and the sticky Ctrl is spent by exactly one key.
 */
class TerminalKeyTests {
    private fun octets(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** The bar is the order the design lists, and nothing else. */
    @Test
    fun order() {
        assertEquals(
            listOf(
                TerminalKey.escape, TerminalKey.tab, TerminalKey.control, TerminalKey.up, TerminalKey.down,
                TerminalKey.left, TerminalKey.right,
                TerminalKey.controlC, TerminalKey.controlD, TerminalKey.controlZ, TerminalKey.controlR,
                TerminalKey.controlL,
                TerminalKey.pipe, TerminalKey.slash, TerminalKey.dash, TerminalKey.tilde, TerminalKey.paste,
            ),
            TerminalKey.bar,
        )
        assertEquals(TerminalKey.allCases.toSet(), TerminalKey.bar.toSet())
        assertEquals("Esc", TerminalKey.bar.map { it.cap }.first())
    }

    /** Each key sends what a terminal expects. */
    @Test
    fun bytes() {
        assertContentEquals(octets(0x1b), TerminalKey.escape.bytes)
        assertContentEquals(octets(0x09), TerminalKey.tab.bytes)
        assertContentEquals(octets(0x1b, 0x5b, 0x41), TerminalKey.up.bytes)
        assertContentEquals(octets(0x1b, 0x5b, 0x42), TerminalKey.down.bytes)
        assertContentEquals(octets(0x1b, 0x5b, 0x43), TerminalKey.right.bytes)
        assertContentEquals(octets(0x1b, 0x5b, 0x44), TerminalKey.left.bytes)
        assertContentEquals(octets(0x03), TerminalKey.controlC.bytes)
        assertContentEquals(octets(0x04), TerminalKey.controlD.bytes)
        assertContentEquals(octets(0x1a), TerminalKey.controlZ.bytes)
        assertContentEquals(octets(0x12), TerminalKey.controlR.bytes)
        assertContentEquals(octets(0x0c), TerminalKey.controlL.bytes)
        assertContentEquals(octets(0x7c), TerminalKey.pipe.bytes)
        assertContentEquals(octets(0x2f), TerminalKey.slash.bytes)
        assertContentEquals(octets(0x2d), TerminalKey.dash.bytes)
        assertContentEquals(octets(0x7e), TerminalKey.tilde.bytes)
    }

    /** Ctrl and Paste act rather than type. */
    @Test
    fun actions() {
        assertNull(TerminalKey.control.bytes)
        assertNull(TerminalKey.paste.bytes)
    }

    /** A letter held with Ctrl is that letter with its top bits cleared. */
    @Test
    fun controlBytes() {
        assertContentEquals(octets(0x03), TerminalControlBytes.forCharacter('c'))
        assertContentEquals(octets(0x03), TerminalControlBytes.forCharacter('C'))
        assertContentEquals(octets(0x04), TerminalControlBytes.forCharacter('d'))
        assertContentEquals(octets(0x1b), TerminalControlBytes.forCharacter('['))
        // The two outside the band every terminal still maps.
        assertContentEquals(octets(0x00), TerminalControlBytes.forCharacter(' '))
        assertContentEquals(octets(0x7f), TerminalControlBytes.forCharacter('?'))
        // Nothing is invented for a character no terminal has a control for.
        assertNull(TerminalControlBytes.forCharacter('1'))
        assertNull(TerminalControlBytes.forCharacter('中'))
    }

    /** The sticky Ctrl changes one key and then releases. */
    @Test
    fun latchSpentByOneKey() {
        val latch = ControlLatch()
        assertFalse(latch.isArmed)
        // Not armed: bytes go as they are.
        assertContentEquals(octets(0x63), latch.apply(octets(0x63)))

        latch.toggle()
        assertTrue(latch.isArmed)
        assertContentEquals(octets(0x03), latch.apply(octets(0x63)), "c becomes Ctrl-C")
        assertFalse(latch.isArmed, "and the latch is spent")
        assertContentEquals(octets(0x63), latch.apply(octets(0x63)), "so the next c is a c")
    }

    /** A second tap on Ctrl disarms it, so an armed bar is never a trap. */
    @Test
    fun latchToggles() {
        val latch = ControlLatch()
        latch.toggle()
        latch.toggle()
        assertFalse(latch.isArmed)
        assertContentEquals(octets(0x63), latch.apply(octets(0x63)))
    }

    /** Anything that is not one character spends the latch unchanged. */
    @Test
    fun latchPassesSequencesThrough() {
        val latch = ControlLatch()
        latch.toggle()
        assertContentEquals(octets(0x1b, 0x5b, 0x41), latch.apply(octets(0x1b, 0x5b, 0x41)), "an arrow is an arrow")
        assertFalse(latch.isArmed)

        latch.toggle()
        assertContentEquals(octets(0x31), latch.apply(octets(0x31)), "a digit has no control byte")
        assertFalse(latch.isArmed)
    }

    /** The type size is remembered inside the bounds a phone can show. */
    @Test
    fun typeSize() {
        assertEquals(TerminalTypeSize.minimum, TerminalTypeSize.clamp(4.0))
        assertEquals(TerminalTypeSize.maximum, TerminalTypeSize.clamp(99.0))
        assertEquals(TerminalTypeSize.standard, TerminalTypeSize.clamp(Double.NaN))
        assertEquals(18.0, TerminalTypeSize.scaled(12.0, by = 1.5))
        assertEquals(TerminalTypeSize.minimum, TerminalTypeSize.scaled(12.0, by = 0.25))
        assertEquals(12.0, TerminalTypeSize.scaled(12.0, by = 0.0))
    }
}
