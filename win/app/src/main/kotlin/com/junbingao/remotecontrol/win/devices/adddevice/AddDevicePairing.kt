package com.junbingao.remotecontrol.win.devices.adddevice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.PairingProgress
import com.junbingao.remotecontrol.core.protocol.PairingStep
import com.junbingao.remotecontrol.core.state.GatewayAPI
import com.junbingao.remotecontrol.core.transport.PairingGrant
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.shared.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * One visit to the Add device modal (`AddDeviceModal.tsx`, with `usePairingProgress`): the code it
 * minted, the handshake frames that name that code, and what the reader asked it to show. A new one
 * is made each time the modal opens, so each visit requests exactly one pairing code.
 */
class AddDevicePairing {
    /** Which of the two one-liners was just copied, for its button to say so. */
    enum class Copied { code, scan }

    var grant: PairingGrant? by mutableStateOf(null)
        private set

    /**
     * The request for a code failed. The web never takes the line back, not even when a later New
     * code goes through.
     */
    var failed: Boolean by mutableStateOf(false)
        private set
    var manual: Boolean by mutableStateOf(false)
    var copied: Copied? by mutableStateOf(null)
        private set

    /** When the modal started listening, so it can say how long. */
    val openedAt: Long = Format.nowMillis

    /** The newest `pairing.progress` frame, for whichever code it names. */
    private var latest: PairingProgress? by mutableStateOf(null)
    private var copiedReset: Job? = null

    /** The handshake of this visit's code; nothing while another code's progress is the one in flight. */
    val live: PairingProgress?
        get() {
            val grant = grant ?: return null
            val latest = latest ?: return null
            return if (latest.code == grant.code) latest else null
        }

    val step: PairingStep? get() = live?.step

    /** The device answered: it is enrolled and its socket is up. */
    val connected: Boolean get() = step == PairingStep.online || step == PairingStep.agents

    /**
     * The one command to run on the host. The gateway hands out `install.macos` and `install.linux`
     * so a platform whose command really differs can be added without a wire change, but the two are
     * the same string today: the installer tells macOS from Linux itself, so nobody is asked which.
     */
    val command: String get() = grant?.install?.macos ?: ""

    /** Ask the gateway for a code — on opening, and again for New code. */
    suspend fun request(api: GatewayAPI?) {
        try {
            grant = (api ?: throw TransportError.NotConnected).beginPairing()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    fun receive(frame: AppFrame) {
        if (frame is AppFrame.PairingProgress) latest = frame.progress
    }

    /** The code a closing modal gives back: one the device has not claimed. */
    val unclaimedCode: String?
        get() {
            val grant = grant ?: return null
            return if (connected) null else grant.code
        }

    /** Put the text on the clipboard, and say Copied for 1.4 s. */
    fun copy(text: String, target: Copied, scope: CoroutineScope) {
        Clipboard.write(text)
        copied = target
        copiedReset?.cancel()
        copiedReset = scope.launch {
            delay(1400)
            copied = null
        }
    }

    /** How long the code has left on the gateway's clock, in milliseconds. */
    fun remaining(now: Long): Long {
        val grant = grant ?: return 0
        return max(0, grant.expiresAt - now)
    }

    fun hasExpired(now: Long): Boolean = grant != null && remaining(now) == 0L && !connected
}
