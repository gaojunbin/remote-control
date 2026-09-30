package com.junbingao.remotecontrol.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.Base64

/**
 * Amendment A38: bytes one terminal produced, on their way to the one app connection that holds
 * it.
 *
 * The gateway strips the device's `to` and adds `device_id`, so an app reads which machine the
 * bytes came from and never learns which connection was addressed. `seq` starts at 1 and rises by
 * one per frame per terminal, which is how a gap is told from a pause (protocol 7.3).
 */
data class TerminalOutput(
    val terminalID: String,
    val deviceID: String,
    val seq: Int,
    /**
     * The bytes, still base64. Decoding is the screen's job, and a frame that is not valid base64
     * is dropped rather than fed as rubbish.
     */
    val data: String,
) {
    val bytes: ByteArray? get() = decodeBase64(data)

    companion object {
        operator fun invoke(json: JsonElement): TerminalOutput {
            val frame = json.objectValue
            val terminalID = frame?.string("terminal_id")
            val deviceID = frame?.string("device_id")
            val seq = frame?.int("seq")
            if (terminalID == null || deviceID == null || seq == null) throw ProtocolFailure.Malformed("terminal.output")
            return TerminalOutput(terminalID = terminalID, deviceID = deviceID, seq = seq, data = frame.string("data") ?: "")
        }
    }
}

/**
 * Amendment A38: the shell ended and the terminal is gone. `code` is null where the device could
 * not read one, which is why it is nullable here and not a zero.
 */
data class TerminalExited(val terminalID: String, val deviceID: String, val code: Int?) {
    companion object {
        operator fun invoke(json: JsonElement): TerminalExited {
            val frame = json.objectValue
            val terminalID = frame?.string("terminal_id")
            val deviceID = frame?.string("device_id")
            if (terminalID == null || deviceID == null) throw ProtocolFailure.Malformed("terminal.exited")
            return TerminalExited(terminalID = terminalID, deviceID = deviceID, code = frame.int("code"))
        }
    }
}

/** The reply to `terminal.open`: the id every later request names. */
@Serializable
data class TerminalOpenResult(@SerialName("terminal_id") val terminalID: String)

/**
 * The reply to `terminal.attach`: the size the shell is running at and the last 64 KiB it
 * produced, so the emulator is caught up before output resumes.
 */
@Serializable
data class TerminalAttachResult(
    @SerialName("terminal_id") val terminalID: String,
    val cols: Int = 80,
    val rows: Int = 24,
    val scrollback: String = "",
) {
    val scrollbackBytes: ByteArray? get() = decodeBase64(scrollback)
}

/**
 * The bounds the device enforces on a terminal (protocol 6.3, A38). The app clamps to them so a
 * rotation into an odd size is never a refused request.
 */
object TerminalLimits {
    const val minCols = 1
    const val maxCols = 500
    const val minRows = 1
    const val maxRows = 200

    /** At most 64 KiB decoded in one `terminal.input`. */
    const val maxInputBytes = 64 * 1024

    fun cols(value: Int): Int = value.coerceIn(minCols, maxCols)
    fun rows(value: Int): Int = value.coerceIn(minRows, maxRows)
}

/** `Data(base64Encoded:)`: padded standard base64 and nothing else, or null. */
internal fun decodeBase64(text: String): ByteArray? {
    if (text.length % 4 != 0) return null
    return try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}
