package com.junbingao.remotecontrol.win.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.state.TerminalSession
import com.junbingao.remotecontrol.core.state.TerminalSize
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.device
import com.junbingao.remotecontrol.win.notifications.SettingsFeature
import com.junbingao.remotecontrol.win.platform.TerminalFeed
import com.junbingao.remotecontrol.win.shared.ErrorText
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * `useTerminal.ts` — A38 §7.3: one terminal on one device, from this app connection. The core's
 * `TerminalSession` is the whole protocol side — open, attach, input, resize, close, `seq` gaps and
 * repeats — and this is the web page's own rules on top of it:
 *
 * - the shell is started once the emulator has measured itself, and only while the device is
 *   online, offers one, and the socket is open;
 * - after a lost socket, or the device coming back, the terminal is taken over again, and an
 *   attach's scrollback is drawn on a reset emulator: it is the screen as it was left, and
 *   appending it would print everything the person already read a second time;
 * - an answer that comes back after the page was left is closed rather than left running for ten
 *   minutes with nobody attached;
 * - leaving the page ends the shell.
 *
 * What a shell writes is bytes, decoded once, by the emulator. Nothing here logs, stores or
 * inspects them.
 */
class TerminalScreen(val deviceID: String, model: WinAppModel) {
    /** The status line's word (`TerminalStatus` in `useTerminal.ts`). */
    enum class Status { connecting, connected, disconnected, exited }

    val feed = TerminalFeed()

    /** True once a shell has run here, which is what makes a loss a loss. */
    var started: Boolean by mutableStateOf(false)
        private set

    private val reference = WeakReference(model)
    private val model: WinAppModel? get() = reference.get()
    private val tasks = model.tasks
    private val failures = TerminalFailures()
    private val session = TerminalSession(
        deviceID = deviceID,
        channel = TerminalRequests(socket = { reference.get()?.connection?.channel }, failures = failures),
        tasks = model.tasks,
    )
    private val frameToken = "terminal.${UUID.randomUUID()}"
    private var size: TerminalSize? = null
    private var isShown = false
    private var attempting = false
    private var again = false
    private var resetBeforeScrollback = false

    // What the page reads

    /** Rule 20: a device that is offline or offers no terminal is never asked for one. */
    val available: Boolean
        get() {
            val device = model?.device(deviceID) ?: return false
            return device.online && device.offersTerminal
        }

    val socketOpen: Boolean get() = model?.connectionIsOpen ?: false

    val status: Status
        get() {
            val current = session.status
            if (current is TerminalSession.Status.Exited) return Status.exited
            if (!available || !socketOpen) return if (started) Status.disconnected else Status.connecting
            return when (current) {
                TerminalSession.Status.Connected -> Status.connected
                TerminalSession.Status.Disconnected, is TerminalSession.Status.Failed -> Status.disconnected
                else -> Status.connecting
            }
        }

    /** The shell's exit code, or null when the device never said (§7.3). */
    val exitCode: Int? get() = (session.status as? TerminalSession.Status.Exited)?.code

    /** Why the last attempt failed, in the reader's language, read now. */
    val reason: String?
        get() {
            if (!available || !socketOpen || session.status !is TerminalSession.Status.Failed) return null
            return failures.latest?.let { ErrorText.text(it) } ?: S.errors.generic
        }

    /** §7.3: a `seq` that skipped, so bytes were dropped rather than delayed. */
    val missedOutput: Boolean get() = session.missedOutput

    // The page's life

    /**
     * The page is on screen: frames for this terminal reach the session, a sign-out can end it,
     * and the shell starts if the emulator has already measured itself.
     */
    fun show() {
        val model = model ?: return
        if (isShown) return
        isShown = true
        session.onOutput = { bytes -> output(bytes) }
        model.connection.addFrameHandler(frameToken) { frame -> session.receive(frame) }
        SettingsFeature.state(of = model).track(this)
        connect()
    }

    /** Leaving the page — Close, the back arrow, any other route, a sign-out — ends the shell. */
    fun close() {
        if (!isShown) return
        isShown = false
        session.close()
        val model = model ?: return
        model.connection.removeFrameHandler(frameToken)
        SettingsFeature.state(of = model).forget(this)
    }

    /**
     * A sign-out ends the shell while the connection still names the account: the page's own close
     * goes out by itself and would lose the race with the socket closing, so this one is waited
     * for. The device takes a second close of the same terminal as the first.
     */
    suspend fun end() {
        if (!isShown) return
        val terminalID = session.terminalID
        close()
        val channel = model?.connection?.channel
        if (terminalID == null || channel == null) return
        try {
            channel.request(GatewayRequest.terminalClose(deviceID = deviceID, terminalID = terminalID))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The device ends a terminal nobody is attached to by itself.
        }
    }

    /** The emulator measured itself: the first size starts the shell, and every later one is sent once the dragging stops. */
    fun resized(next: TerminalSize) {
        val first = size == null
        size = next
        session.resize(cols = next.cols, rows = next.rows)
        if (first) connect()
    }

    fun type(bytes: ByteArray) = session.type(bytes)

    // Connecting

    /** Open a shell, or take back the one this page had: on the first size, when the socket or the device comes back, and on Reconnect. */
    fun connect() = attempt(afterExit = false)

    /** New shell: the ended one is behind the page, and another is asked for. */
    fun restart() {
        started = false
        attempt(afterExit = true)
    }

    private fun attempt(afterExit: Boolean) {
        val size = size
        if (!isShown || size == null || !available || !socketOpen) return
        if (session.status is TerminalSession.Status.Exited && !afterExit) return
        if (attempting) {
            again = true
            return
        }
        attempting = true
        tasks.launch {
            run(size)
            attempting = false
            if (again) {
                again = false
                connect()
            }
        }
    }

    private suspend fun run(size: TerminalSize) {
        if (session.terminalID != null) {
            resetBeforeScrollback = true
            session.attach()
            // An empty scrollback feeds nothing, and the screen is reset all the same.
            if (resetBeforeScrollback && session.status == TerminalSession.Status.Connected) feed.reset()
            resetBeforeScrollback = false
        } else {
            session.open(cols = size.cols, rows = size.rows)
            if (session.terminalID != null) started = true
        }
        if (!isShown) session.close()
    }

    private fun output(bytes: ByteArray) {
        if (resetBeforeScrollback) {
            resetBeforeScrollback = false
            feed.reset()
        }
        feed.write(bytes)
    }
}
