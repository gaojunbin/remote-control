package com.junbingao.remotecontrol.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The phone's key bar, as a table from key to bytes (amendment A38, `docs/DESIGN.md` § "The
 * terminal" → **The phone's key bar**).
 *
 * Nothing here is app-specific: every key sends the sequence a terminal expects, so the shell on the
 * other side cannot tell the bar from a keyboard. It is a table on purpose — the bar draws it and the
 * tests read it, and neither needs a view to do so.
 */
enum class TerminalKey(val rawValue: String) {
    escape("escape"),
    tab("tab"),
    control("control"),
    up("up"),
    down("down"),
    left("left"),
    right("right"),
    controlC("controlC"),
    controlD("controlD"),
    controlZ("controlZ"),
    controlR("controlR"),
    controlL("controlL"),
    pipe("pipe"),
    slash("slash"),
    dash("dash"),
    tilde("tilde"),
    paste("paste");

    val id: String get() = rawValue

    /**
     * What the key sends, or null for the two that act rather than type: the sticky Ctrl, which
     * changes the next key, and Paste, which reads the clipboard. A fresh array every time, so no
     * caller can change what the next one sends.
     */
    val bytes: ByteArray?
        get() = when (this) {
            escape -> bytesOf(0x1b)
            tab -> bytesOf(0x09)
            control -> null
            up -> bytesOf(0x1b, 0x5b, 0x41)
            down -> bytesOf(0x1b, 0x5b, 0x42)
            right -> bytesOf(0x1b, 0x5b, 0x43)
            left -> bytesOf(0x1b, 0x5b, 0x44)
            controlC -> bytesOf(0x03)
            controlD -> bytesOf(0x04)
            controlZ -> bytesOf(0x1a)
            controlR -> bytesOf(0x12)
            controlL -> bytesOf(0x0c)
            pipe -> bytesOf(0x7c)
            slash -> bytesOf(0x2f)
            dash -> bytesOf(0x2d)
            tilde -> bytesOf(0x7e)
            paste -> null
        }

    /**
     * The cap, written the way the design writes it. These are key names and not the app's own
     * words, so they are the same in every language — all but Paste, which is a verb.
     */
    val cap: String
        get() = when (this) {
            escape -> "Esc"
            tab -> "Tab"
            control -> "Ctrl"
            up -> "↑"
            down -> "↓"
            left -> "←"
            right -> "→"
            controlC -> "Ctrl-C"
            controlD -> "Ctrl-D"
            controlZ -> "Ctrl-Z"
            controlR -> "Ctrl-R"
            controlL -> "Ctrl-L"
            pipe -> "|"
            slash -> "/"
            dash -> "-"
            tilde -> "~"
            paste -> L10n.string("Paste")
        }

    /** What assistive technology says instead of the glyph, where the glyph is not a word. A cap that already reads aloud is its own label. */
    val spokenName: String
        get() = when (this) {
            up -> L10n.string("Up arrow")
            down -> L10n.string("Down arrow")
            left -> L10n.string("Left arrow")
            right -> L10n.string("Right arrow")
            pipe -> L10n.string("Pipe")
            slash -> L10n.string("Slash")
            dash -> L10n.string("Hyphen")
            tilde -> L10n.string("Tilde")
            else -> cap
        }

    companion object {
        val allCases: List<TerminalKey> get() = entries

        operator fun invoke(rawValue: String): TerminalKey? = entries.firstOrNull { it.rawValue == rawValue }

        /**
         * The bar, in the order the design lists it. [allCases] follows the declaration, and the order
         * is the design's ruling rather than an accident of it, so it is stated here.
         */
        val bar: List<TerminalKey> = listOf(
            escape, tab, control, up, down, left, right,
            controlC, controlD, controlZ, controlR, controlL,
            pipe, slash, dash, tilde, paste,
        )
    }
}

private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/**
 * The bytes one printable character sends when Ctrl is held.
 *
 * The rule is the one every terminal uses: a character in the `@`…`_` band is sent with its top bits
 * cleared, lower case counting as upper. Space is NUL and `?` is DEL, which are the two the band does
 * not cover.
 */
object TerminalControlBytes {
    fun forCharacter(character: Char): ByteArray? {
        val ascii = character.code
        if (ascii >= 0x80) return null
        if (ascii == 0x20) return bytesOf(0x00)
        if (ascii == 0x3f) return bytesOf(0x7f)
        val upper = if (ascii in 0x61..0x7a) ascii - 0x20 else ascii
        if (upper !in 0x40..0x5f) return null
        return bytesOf(upper and 0x1f)
    }
}

/**
 * The sticky Ctrl: armed by a tap, spent by the next key, and visible while it waits
 * (`docs/DESIGN.md` § "The terminal").
 *
 * A second tap disarms it, so an armed bar is never a trap. Anything that is not one character — a
 * paste, a key that already sends a sequence — spends the latch without being changed, because a
 * person who armed Ctrl and then pressed an arrow meant the arrow.
 *
 * The screen holds one and draws [isArmed], which is snapshot state, so the Ctrl cap lights and
 * goes out without the screen keeping a copy of its own.
 */
class ControlLatch {
    var isArmed: Boolean by mutableStateOf(false)
        private set

    /** The tap on Ctrl itself. */
    fun toggle() {
        isArmed = !isArmed
    }

    fun disarm() {
        isArmed = false
    }

    /** What to send for these bytes, spending the latch if it was armed. */
    fun apply(bytes: ByteArray): ByteArray {
        if (!isArmed) return bytes
        isArmed = false
        if (bytes.size != 1) return bytes
        return TerminalControlBytes.forCharacter((bytes[0].toInt() and 0xFF).toChar()) ?: bytes
    }

    override fun equals(other: Any?): Boolean = other is ControlLatch && isArmed == other.isArmed

    override fun hashCode(): Int = isArmed.hashCode()

    override fun toString(): String = "ControlLatch(isArmed=$isArmed)"
}
