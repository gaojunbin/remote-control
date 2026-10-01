package com.junbingao.remotecontrol.win.platform

/**
 * The one handle the page holds on the emulator: bytes go in, and nothing comes back out through
 * it. The emulator is a Swing component the page embeds, so a recomposition cannot carry a byte
 * stream; this is the wire between them, and the emulator installs its end.
 */
class TerminalFeed {
    internal var writer: ((ByteArray) -> Unit)? = null

    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        writer?.invoke(bytes)
    }

    /** `term.reset()`: a full reset, asked for the way a shell asks for one. */
    fun reset() = write(fullReset)

    companion object {
        /** RIS, `ESC c`. */
        val fullReset = byteArrayOf(0x1B, 0x63)
    }
}
