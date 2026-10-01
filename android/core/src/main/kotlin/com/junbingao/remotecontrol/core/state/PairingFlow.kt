package com.junbingao.remotecontrol.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.PairingStep
import com.junbingao.remotecontrol.core.transport.InstallCommands
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** Drives the "Add device" sheet: one short-lived code, the install one-liner, and the live checklist the gateway pushes as the device comes up. */
class PairingFlow(private val api: GatewayAPI) {
    data class Step(val id: PairingStep, val title: String, val done: Boolean)

    /**
     * The code this flow is following. Amendment A23: it is minted here for the code flow and handed
     * over by a claim for the scan flow, and only the first of those carries an install command — the
     * host that printed a QR code has already run one.
     */
    data class Pairing(val code: String, val expiresAt: Long, val install: InstallCommands? = null)

    var pairing: Pairing? by mutableStateOf(null)
        private set
    var reached: PairingStep by mutableStateOf(PairingStep.waiting)
        private set
    var pairedDevice: Device? by mutableStateOf(null)
        private set
    var errorMessage: String? by mutableStateOf(null)
        private set
    var isRequesting: Boolean by mutableStateOf(false)
        private set

    val command: String get() = pairing?.install?.command ?: ""

    val code: String get() = pairing?.code ?: ""

    val isComplete: Boolean get() = pairedDevice != null && reached == PairingStep.agents

    fun expiry(now: Instant = Instant.now()): String {
        val pairing = pairing ?: return ""
        return RelativeTime.countdown(to = pairing.expiresAt, now = now)
    }

    fun hasExpired(now: Instant = Instant.now()): Boolean {
        val pairing = pairing ?: return false
        return pairing.expiresAt.toDouble() / 1000 <= now.epochSecond + now.nano / 1_000_000_000.0
    }

    val steps: List<Step>
        get() = listOf(
            Step(id = PairingStep.waiting, title = L10n.string("Gateway ready"), done = true),
            Step(id = PairingStep.enrolled, title = L10n.string("Device handshake"),
                 done = reached.order >= PairingStep.enrolled.order),
            Step(id = PairingStep.online, title = L10n.string("Device online"),
                 done = reached.order >= PairingStep.online.order),
            Step(id = PairingStep.agents, title = L10n.string("Detect installed agents"),
                 done = reached.order >= PairingStep.agents.order),
        )

    /**
     * Agent names the paired device reported, for the last checklist row. The names, not the ids:
     * this line is the first thing a new machine says about itself, and the app names every agent it
     * knows (A25).
     */
    val detectedAgents: String get() = pairedDevice?.availableAgents?.joinToString(" · ") { it.displayName } ?: ""

    suspend fun begin() {
        if (isRequesting) return
        isRequesting = true
        errorMessage = null
        try {
            val grant = api.beginPairing()
            pairing = Pairing(code = grant.code, expiresAt = grant.expiresAt, install = grant.install)
            reached = PairingStep.waiting
            pairedDevice = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = (error as? TransportError)?.errorDescription ?: error.localizedDescription
        } finally {
            isRequesting = false
        }
    }

    /**
     * Amendment A23: follow the code the gateway minted for a scanned host. The code this sheet was
     * already showing is given back first, so one sheet never leaves two codes outstanding.
     */
    suspend fun claim(token: String) {
        val claim = api.claimPairingRequest(token = token)
        cancel()
        pairing = Pairing(code = claim.code, expiresAt = claim.expiresAt)
        errorMessage = null
    }

    /**
     * Give the code back. The screen keeps showing it until the gateway has taken it: blanking first
     * put the "Requesting a code" placeholder on a sheet that was about to close (owner's report,
     * 2026-09-18), and a scan's claim swaps codes without a gap for the same reason.
     */
    suspend fun cancel() {
        val code = pairing?.code ?: return
        attempt { api.cancelPairing(code = code) }
        if (pairing?.code != code) return
        pairing = null
        reached = PairingStep.waiting
        pairedDevice = null
    }

    fun receive(frame: AppFrame) {
        if (frame !is AppFrame.PairingProgress || frame.progress.code != pairing?.code) return
        val progress = frame.progress
        if (progress.step.order >= reached.order) reached = progress.step
        progress.device?.let { pairedDevice = it }
    }
}
