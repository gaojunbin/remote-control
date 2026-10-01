package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.TerminalLimits
import com.junbingao.remotecontrol.core.trimmingWhitespaces

/**
 * The shell behind a demo terminal (amendment A38).
 *
 * It prints a prompt, echoes what is typed, answers a line with the line, and ends on `exit` or
 * Ctrl-D — enough to drive the screen, the key bar and the reconnect without a machine to reach.
 * Its output is bytes the way a real shell's is, so the emulator on the other side is doing its
 * real job.
 *
 * What it prints is not the app's own words and is never translated: it stands in for a machine,
 * and a machine writes in its own language.
 */
class DemoShell(cols: Int = 80, rows: Int = 24) {
    /** The last 64 KiB it produced, which is what an `attach` replies with. */
    var scrollback: ByteArray = ByteArray(0)
        private set
    var cols: Int = cols
        private set
    var rows: Int = rows
        private set
    private var line = ""

    /** What the shell wrote back to what it was fed, and its exit code once it has ended. */
    class Response(val output: ByteArray, val code: Int?)

    companion object {
        /** Protocol 7.3: the ring a device keeps per terminal. */
        const val scrollbackLimit = 64 * 1024
        private const val prompt = "demo:~$ "
    }

    fun start(): ByteArray = emit("Remote Control demo shell — nothing here reaches a real machine.\r\n$prompt")

    fun resize(cols: Int, rows: Int) {
        this.cols = TerminalLimits.cols(cols)
        this.rows = TerminalLimits.rows(rows)
    }

    /**
     * Feeds typed bytes. The output is what the shell writes back; the code is set once the shell
     * has ended, and nothing should be fed to it after.
     */
    fun feed(input: ByteArray): Response {
        val written = StringBuilder()
        for (byte in input) {
            when (val value = byte.toInt() and 0xFF) {
                0x0d, 0x0a -> {
                    written.append("\r\n")
                    answer(to = line.trimmingWhitespaces(), into = written)?.let { code ->
                        return Response(emit(written.toString()), code)
                    }
                    line = ""
                    written.append(prompt)
                }
                0x7f, 0x08 -> {
                    if (line.isEmpty()) continue
                    line = line.dropLast(1)
                    written.append("\u0008 \u0008")
                }
                0x03 -> {
                    line = ""
                    written.append("^C\r\n$prompt")
                }
                0x04 -> {
                    written.append("exit\r\n")
                    return Response(emit(written.toString()), 0)
                }
                0x09 -> {
                    line += "\t"
                    written.append("\t")
                }
                in 0x20..0x7e -> {
                    val character = value.toChar()
                    line += character
                    written.append(character)
                }
                // Anything else — an arrow, a control the demo has no use for — is swallowed
                // rather than printed as rubbish.
                else -> continue
            }
        }
        return Response(emit(written.toString()), null)
    }

    /**
     * What one line of input answers with, or an exit code when it ends the shell. Everything
     * this shell knows is here, and it is deliberately little: a demo that pretends to be a
     * machine invites being trusted as one.
     */
    private fun answer(to: String, into: StringBuilder): Int? {
        when (to) {
            "" -> return null
            "exit", "logout" -> {
                into.append("logout\r\n")
                return 0
            }
            "pwd" -> into.append("/Users/me\r\n")
            "size" -> into.append("${cols}x$rows\r\n")
            "help" -> into.append("This is a demo. Try: pwd, size, exit. Anything else is echoed back.\r\n")
            else -> into.append("$to\r\n")
        }
        return null
    }

    private fun emit(text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        val kept = scrollback + bytes
        scrollback = if (kept.size > scrollbackLimit) kept.copyOfRange(kept.size - scrollbackLimit, kept.size) else kept
        return bytes
    }
}
