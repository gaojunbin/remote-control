package com.junbingao.remotecontrol.win.platform

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The emulator's end of the wire: JediTerm reads characters from a tty, and this tty is the
 * device's byte stream, decoded as UTF-8 as it arrives — a character split across two chunks is
 * held until its last byte comes. What the person types goes to `onInput` as the bytes JediTerm
 * encodes, and a new grid size to `onSize`, both on the Swing thread the window's Compose runs on.
 */
internal class FeedConnector : TtyConnector {
    @Volatile var onInput: (ByteArray) -> Unit = {}
    @Volatile var onSize: (Int, Int) -> Unit = { _, _ -> }

    private val chunks = LinkedBlockingQueue<ByteArray>()
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteBuffer.allocate(0)
    private val decoded = CharBuffer.allocate(8192).flip()
    @Volatile private var open = true

    /** Bytes from the device. */
    fun feed(bytes: ByteArray) {
        chunks.put(bytes.copyOf())
    }

    /** Whether the last decode stopped because the characters filled the buffer, with bytes left to decode. */
    private var overflowed = false

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        while (open && !decoded.hasRemaining()) {
            val chunk = if (overflowed) ByteArray(0) else chunks.poll(100, TimeUnit.MILLISECONDS) ?: continue
            decode(chunk)
        }
        if (!decoded.hasRemaining()) return -1
        val count = minOf(length, decoded.remaining())
        decoded.get(buffer, offset, count)
        return count
    }

    private fun decode(chunk: ByteArray) {
        val input = ByteBuffer.allocate(pending.remaining() + chunk.size).put(pending).put(chunk).flip()
        decoded.compact()
        overflowed = decoder.decode(input, decoded, false).isOverflow
        decoded.flip()
        pending = ByteBuffer.allocate(input.remaining()).put(input).flip()
    }

    override fun write(bytes: ByteArray) {
        if (bytes.isNotEmpty()) onInput(bytes)
    }

    override fun write(string: String) = write(string.toByteArray(Charsets.UTF_8))

    override fun isConnected(): Boolean = open

    override fun resize(termSize: TermSize) {
        if (termSize.columns > 0 && termSize.rows > 0) onSize(termSize.columns, termSize.rows)
    }

    override fun waitFor(): Int {
        while (open) Thread.sleep(100)
        return 0
    }

    override fun ready(): Boolean = decoded.hasRemaining() || chunks.isNotEmpty()

    override fun getName(): String = "remote"

    override fun close() {
        open = false
    }
}
