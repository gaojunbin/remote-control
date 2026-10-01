package com.junbingao.remotecontrol.win.sessions.drawer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DirectoryListing
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.RecentDirectory
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionResult
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import com.junbingao.remotecontrol.core.state.GatewayChannel
import com.junbingao.remotecontrol.core.state.request
import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.shared.SessionOptions
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * What the New session drawer holds (`NewSessionDrawer.tsx`'s form). A new one is made each time
 * the drawer opens, so every field starts from a fresh default derived from the device and its
 * agent rather than being reset.
 *
 * The device list itself is not kept: it is the online devices in name order, read afresh on every
 * draw, so a device that goes offline leaves the menu.
 */
class NewSessionForm(devices: List<Device>, preset: String?) {
    var deviceID: String? by mutableStateOf(if (preset != null && devices.any { it.deviceID == preset }) preset else devices.firstOrNull()?.deviceID)
    var pickedAgent: String? by mutableStateOf(null)
        private set

    /**
     * What the reader chose instead of the agent's own defaults. Cleared with the agent, because a
     * model id belongs to one agent and means nothing to another.
     */
    var options: SessionOptions by mutableStateOf(SessionOptions())
    var cwd: String by mutableStateOf("")

    /** Whether the reader typed or picked a path, which a device change keeps. */
    var cwdTouched: Boolean by mutableStateOf(false)
        private set
    var worktree: Boolean by mutableStateOf(false)
    var recent: List<RecentDirectory> by mutableStateOf(emptyList())
        private set
    var browsing: Boolean by mutableStateOf(false)
    var busy: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set
    val probe = DirectoryProbe()

    fun device(devices: List<Device>): Device? = devices.firstOrNull { it.deviceID == deviceID }

    /** The agent the reader picked, else the first one installed, else the first. */
    fun agent(of: Device?): AgentInfo? {
        val agents = of?.agents.orEmpty()
        return agents.firstOrNull { it.agent == pickedAgent } ?: agents.firstOrNull { it.available } ?: agents.firstOrNull()
    }

    fun chooseDevice(id: String) {
        deviceID = id
        pickedAgent = null
        options = SessionOptions()
        if (!cwdTouched) cwd = ""
    }

    fun chooseAgent(id: String) {
        pickedAgent = id
        options = SessionOptions()
    }

    fun setPath(path: String) {
        cwd = path
        cwdTouched = true
    }

    /**
     * The device's home listing, which seeds the recent list and — while the field is still empty —
     * the working directory.
     */
    suspend fun loadHome(channel: GatewayChannel?) {
        val deviceID = deviceID ?: return
        if (channel == null) return
        try {
            val listing = channel.request(GatewayRequest.dirs(deviceID = deviceID), DirectoryListing.serializer())
            currentCoroutineContext().ensureActive()
            recent = listing.recent
            if (cwd.isEmpty()) cwd = listing.recent.firstOrNull()?.path ?: listing.path
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recent = emptyList()
        }
    }

    // The session it would start

    fun model(of: AgentInfo?): String? = options.model ?: of?.defaultModel
    fun effort(of: AgentInfo?): String? = options.effort ?: of?.defaultEffort
    fun permissionMode(of: AgentInfo?): String? = options.permissionMode ?: of?.defaultPermissionMode

    /** A21: null is the standard speed, whether or not the reader said so. */
    val speed: String? get() = options.speed?.id

    fun canWorktree(agent: AgentInfo?): Boolean = agent?.supports(AgentCapability.worktree) ?: false

    fun canStart(device: Device?, agent: AgentInfo?): Boolean =
        !busy && device != null && agent?.available == true && cwd.trimmed.isNotEmpty()

    /**
     * `session.create`, then the conversation it made. What the agent would choose on its own is
     * sent explicitly, and the standard speed and a worktree nobody asked for are left out.
     */
    suspend fun start(device: Device?, agent: AgentInfo?, channel: GatewayChannel?): Session? {
        if (!canStart(device, agent) || device == null || agent == null) return null
        busy = true
        error = null
        try {
            val request = GatewayRequest.createSession(
                deviceID = device.deviceID, agent = agent.agent,
                cwd = cwd.trimmed,
                model = model(of = agent), permissionMode = permissionMode(of = agent), effort = effort(of = agent),
                speed = speed?.let { SpeedChange.Tier(it) },
                worktree = if (canWorktree(agent) && worktree) true else null,
            )
            return (channel ?: throw TransportError.NotConnected).request(request, SessionResult.serializer()).session
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = refusal(failure)
            return null
        } finally {
            busy = false
        }
    }

    companion object {
        /**
         * The device's own sentence, as the web shows `err.message`; a reply with no words of its
         * own says nothing, as the web's empty message does, and a failure that never reached the
         * device is the drawer's own sentence.
         */
        fun refusal(error: Throwable): String? {
            val reply = error as? GatewayErrorBody ?: return S.newSession.startFailed
            return if (reply.message.isEmpty() || reply.message == reply.code.rawValue) null else reply.message
        }
    }
}
