package com.junbingao.remotecontrol.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.TerminalAttachResult
import com.junbingao.remotecontrol.core.protocol.TerminalLimits
import com.junbingao.remotecontrol.core.protocol.TerminalOpenResult
import com.junbingao.remotecontrol.core.protocol.TerminalOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * One shell on one machine, for as long as a screen is looking at it (amendment A38,
 * `docs/DESIGN.md` § "The terminal").
 *
 * Everything the wire needs is here and nothing the emulator needs is: the bytes go out through
 * [onOutput], so this object can be driven and checked without an emulator, and the screen around it
 * holds no protocol knowledge.
 *
 * Two invariants are worth naming. Input is sent by one coroutine in the order it was typed — two
 * concurrent requests would reorder a person's keystrokes — and nothing here ever logs, prints or
 * stores what travels either way. The work it starts runs in [tasks], the scope the owner passes in.
 */
class TerminalSession(
    val deviceID: String,
    private val channel: GatewayChannel,
    private val tasks: CoroutineScope,
    /** How long a run of size changes is allowed to settle before one `terminal.resize` is sent. A rotation is many layout passes. */
    private val resizeDelay: Duration = 100.milliseconds,
) {
    /** What the status line under the title says. */
    sealed interface Status {
        data object Connecting : Status
        data object Connected : Status

        /** The socket is gone; the device keeps the shell for ten minutes. */
        data object Disconnected : Status
        data class Exited(val code: Int?) : Status
        data class Failed(val message: String) : Status
    }

    var status: Status by mutableStateOf(Status.Connecting)
        private set

    /**
     * The id every later request names, and what an `attach` after a lost socket asks for. Null
     * before the first open and after the shell ended.
     */
    var terminalID: String? by mutableStateOf(null)
        private set

    /**
     * Amendment A38: `seq` rises by one per frame, so a jump is output this app never received. It is
     * said in the status line rather than guessed at.
     */
    var missedOutput: Boolean by mutableStateOf(false)
        private set

    /** The bytes the shell produced, already decoded. Set by the screen. */
    var onOutput: ((ByteArray) -> Unit)? = null

    private var lastSeq = 0

    /**
     * True between an `attach` and the first frame that follows it: `seq` carries on from where the
     * other connection left it, which this app never saw, so the first number after an attach is not
     * a gap.
     */
    private var acceptsAnySeq = false
    private var pendingInput = ByteArrayOutputStream()
    private var sender: Job? = null
    private var resizer: Job? = null
    private var size: TerminalSize? = null
    private var sentSize: TerminalSize? = null

    // Opening, attaching, ending

    /** Start a shell at the size the emulator is drawn at. */
    suspend fun open(cols: Int, rows: Int) {
        status = Status.Connecting
        missedOutput = false
        lastSeq = 0
        acceptsAnySeq = false
        sentSize = TerminalSize(cols = cols, rows = rows)
        size = sentSize
        try {
            val result = channel.request(GatewayRequest.terminalOpen(deviceID = deviceID, cols = cols, rows = rows),
                                         TerminalOpenResult.serializer())
            terminalID = result.terminalID
            status = Status.Connected
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            sentSize = null
            status = Status.Failed(message(error))
        }
    }

    /** Take the shell back after a lost socket. The scrollback lands in the emulator before output resumes, so the screen is what it was. */
    suspend fun attach() {
        val terminalID = terminalID ?: return
        status = Status.Connecting
        try {
            val result = channel.request(GatewayRequest.terminalAttach(deviceID = deviceID, terminalID = terminalID),
                                         TerminalAttachResult.serializer())
            result.scrollbackBytes?.takeIf { it.isNotEmpty() }?.let { onOutput?.invoke(it) }
            // The other side's numbering carries on; this app has not seen it.
            acceptsAnySeq = true
            missedOutput = false
            sentSize = TerminalSize(cols = result.cols, rows = result.rows)
            status = Status.Connected
            // The view may have been rotated while the socket was down.
            if (size != null && size != sentSize) scheduleResize()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is GatewayErrorBody && error.code == GatewayErrorCode.notFound) {
                this.terminalID = null
                status = Status.Exited(code = null)
            } else {
                status = Status.Failed(message(error))
            }
        }
    }

    /** End the shell. Idempotent on the device, so leaving twice is safe. */
    fun close() {
        val terminalID = terminalID ?: return
        this.terminalID = null
        sender?.cancel()
        resizer?.cancel()
        pendingInput = ByteArrayOutputStream()
        tasks.launch { attempt { channel.request(GatewayRequest.terminalClose(deviceID = deviceID, terminalID = terminalID)) } }
    }

    // The link under it

    /** The socket came back, or went. A terminal that was connected when the socket dropped attaches again by itself the moment it is up. */
    fun link(isUp: Boolean) {
        if (terminalID == null) return
        if (isUp) {
            if (status != Status.Disconnected) return
            tasks.launch { attach() }
        } else {
            if (status != Status.Connected && status != Status.Connecting) return
            status = Status.Disconnected
        }
    }

    // Typing and size

    /** Bytes the person typed, or a key from the bar. Queued behind whatever is already in flight so they arrive in the order they were made. */
    fun type(bytes: ByteArray) {
        if (bytes.isEmpty() || terminalID == null) return
        pendingInput.write(bytes)
        if (sender != null) return
        // Started only once it is held, so a drain that finishes at once still clears it.
        val drain = tasks.launch(start = CoroutineStart.LAZY) { drainInput() }
        sender = drain
        drain.start()
    }

    private suspend fun drainInput() {
        try {
            while (pendingInput.size() > 0) {
                val terminalID = terminalID ?: return
                val waiting = pendingInput.toByteArray()
                val chunk = waiting.copyOfRange(0, minOf(waiting.size, TerminalLimits.maxInputBytes))
                pendingInput = ByteArrayOutputStream().apply { write(waiting, chunk.size, waiting.size - chunk.size) }
                try {
                    channel.request(GatewayRequest.terminalInput(deviceID = deviceID, terminalID = terminalID, data = chunk))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    note(error)
                    return
                }
            }
        } finally {
            sender = null
        }
    }

    /** The emulator's view changed size. A run of them settles into one request: a rotation lays out many times and the shell needs the last. */
    fun resize(cols: Int, rows: Int) {
        val next = TerminalSize(cols = cols, rows = rows)
        if (size == next) return
        size = next
        if (terminalID == null) return
        scheduleResize()
    }

    private fun scheduleResize() {
        resizer?.cancel()
        resizer = tasks.launch {
            delay(resizeDelay)
            sendSize()
        }
    }

    private suspend fun sendSize() {
        val terminalID = terminalID ?: return
        val size = size ?: return
        if (sentSize == size) return
        sentSize = size
        try {
            channel.request(GatewayRequest.terminalResize(deviceID = deviceID, terminalID = terminalID,
                                                          cols = size.cols, rows = size.rows))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            sentSize = null
            note(error)
        }
    }

    // Frames

    /** Amendment A38: the two frames a terminal produces. Anything for another terminal or another machine is not this screen's. */
    fun receive(frame: AppFrame) {
        when (frame) {
            is AppFrame.TerminalOutput -> {
                if (frame.output.terminalID != terminalID || frame.output.deviceID != deviceID) return
                apply(frame.output)
            }
            is AppFrame.TerminalExited -> {
                if (frame.exited.terminalID != terminalID || frame.exited.deviceID != deviceID) return
                terminalID = null
                status = Status.Exited(code = frame.exited.code)
            }
            else -> Unit
        }
    }

    private fun apply(output: TerminalOutput) {
        if (acceptsAnySeq) {
            acceptsAnySeq = false
        } else if (output.seq <= lastSeq) {
            // A late or duplicate frame. Feeding it twice would print it twice.
            return
        } else if (output.seq != lastSeq + 1) {
            missedOutput = true
        }
        lastSeq = output.seq
        if (status != Status.Connected) status = Status.Connected
        val bytes = output.bytes ?: return
        if (bytes.isEmpty()) return
        onOutput?.invoke(bytes)
    }

    // Failures

    private fun note(error: Throwable) {
        if (error !is GatewayErrorBody) return
        when (error.code) {
            GatewayErrorCode.notFound -> {
                terminalID = null
                status = Status.Exited(code = null)
            }
            GatewayErrorCode.deviceOffline -> status = Status.Disconnected
            else -> status = Status.Failed(error.message)
        }
    }

    /**
     * What the reader is shown when a terminal will not open. The device's own sentence wherever it
     * sent one, and the app's words for the one refusal it can word better: a machine that offers no
     * shell at all.
     */
    private fun message(error: Throwable): String {
        if (error !is GatewayErrorBody) return error.localizedDescription
        return when (error.code) {
            GatewayErrorCode.unsupported -> L10n.string("This device does not offer a terminal.")
            GatewayErrorCode.conflict -> L10n.string("This device already runs four terminals. Close one first.")
            GatewayErrorCode.deviceOffline -> L10n.string("This device is offline.")
            else -> error.message
        }
    }
}

/**
 * The emulator's size, clamped to what the device accepts (A38). A value rather than a pair, so "has
 * it changed since the last request?" is one comparison and reads as one.
 */
class TerminalSize(cols: Int, rows: Int) {
    val cols: Int = TerminalLimits.cols(cols)
    val rows: Int = TerminalLimits.rows(rows)

    override fun equals(other: Any?): Boolean = other is TerminalSize && cols == other.cols && rows == other.rows

    override fun hashCode(): Int = 31 * cols + rows

    override fun toString(): String = "TerminalSize(cols=$cols, rows=$rows)"
}

/**
 * The words the status line uses, kept beside the states they describe so the screen reads as one
 * line of English and the checks can read them too.
 */
object TerminalStatusText {
    fun line(status: TerminalSession.Status): String = when (status) {
        TerminalSession.Status.Connecting -> L10n.string("Connecting")
        TerminalSession.Status.Connected -> L10n.string("Connected")
        TerminalSession.Status.Disconnected -> L10n.string("Disconnected")
        is TerminalSession.Status.Exited -> exited(code = status.code)
        is TerminalSession.Status.Failed -> status.message
    }

    fun exited(code: Int?): String {
        if (code == null) return L10n.string("Shell exited")
        return L10n.string("Shell exited (%lld)", code)
    }

    /** Whether this state is one the person can act on, and with which action. */
    fun offersReconnect(status: TerminalSession.Status): Boolean = status == TerminalSession.Status.Disconnected

    fun offersNewShell(status: TerminalSession.Status): Boolean = when (status) {
        is TerminalSession.Status.Exited, is TerminalSession.Status.Failed -> true
        TerminalSession.Status.Connecting, TerminalSession.Status.Connected, TerminalSession.Status.Disconnected -> false
    }
}
