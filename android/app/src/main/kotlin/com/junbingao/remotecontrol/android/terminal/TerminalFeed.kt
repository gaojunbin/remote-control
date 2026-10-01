package com.junbingao.remotecontrol.android.terminal

import android.os.Handler
import android.os.Looper
import com.junbingao.remotecontrol.core.state.TerminalSize

/**
 * The one handle the terminal screen holds on the emulator: bytes go in, and nothing comes back
 * out through it.
 *
 * The emulator is an Android view that [TerminalHost] owns, so the screen cannot address it
 * directly and a recomposition cannot carry a byte stream. This is the wire between them, and it
 * is deliberately one-way, as `TerminalFeed` is on the iPhone.
 */
class TerminalFeed {
    internal var writer: ((ByteArray) -> Unit)? = null

    /** What the emulator was last laid out at, which is what a fresh `open` asks the device for. */
    var size: TerminalSize? = null
        internal set

    private val main = Handler(Looper.getMainLooper())

    /**
     * Draw bytes the shell sent. The emulator is only ever touched on the main thread, so bytes
     * handed over from anywhere else wait for it. Nothing happens until the view exists.
     */
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            writer?.invoke(bytes)
        } else {
            main.post { writer?.invoke(bytes) }
        }
    }
}
