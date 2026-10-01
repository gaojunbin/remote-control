package com.junbingao.remotecontrol.win.devices.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.AgentAccount
import com.junbingao.remotecontrol.core.protocol.AgentsResult
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.state.GatewayChannel
import com.junbingao.remotecontrol.core.state.request
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.shared.ErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * `useDeviceQuota`: the fresh half of a device page (A33).
 *
 * `hello` and `agents.updated` carry an account without its windows, because they come from files
 * that change rarely. The windows are read on request, so the page asks `device.agents` the moment
 * it opens and keeps the reply here, in the page's own state: nothing with limits in it reaches the
 * stored device, which the list and the new-session drawer read.
 */
class DeviceQuota {
    /** Where the meters stand: waiting, drawn, or replaced by one line saying why. */
    enum class Status { checking, ready, offline, failed }

    var status: Status by mutableStateOf(Status.checking)
        private set

    /** Accounts with their limits, by agent id. Empty until the reply arrives. */
    var accounts: Map<String, List<AgentAccount>> by mutableStateOf(emptyMap())
        private set

    /** Why the request failed, in the device's own words, when it did. */
    var error: String? by mutableStateOf(null)
        private set

    /** Which Refresh this is: part of what the page asks again for. */
    var attempt: Int by mutableIntStateOf(0)
        private set

    fun refresh() {
        attempt += 1
    }

    /**
     * One ask, for one device in one reachability. The page runs it for every change of the three,
     * cancelling the one before, so an answer only ever lands on the question it belongs to. An
     * offline device is not asked at all: it has accounts and no meters, and asking could only time
     * out.
     */
    suspend fun check(deviceID: String, online: Boolean, channel: GatewayChannel?) {
        accounts = emptyMap()
        error = null
        if (!online) {
            status = Status.offline
            return
        }
        status = Status.checking
        try {
            val result = (channel ?: throw TransportError.NotConnected).request(GatewayRequest.agents(deviceID = deviceID), AgentsResult.serializer())
            currentCoroutineContext().ensureActive()
            val fresh = LinkedHashMap<String, List<AgentAccount>>()
            for (agent in result.agents) agent.accounts?.let { fresh[agent.agent] = it }
            accounts = fresh
            status = Status.ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if ((failure as? GatewayErrorBody)?.code == GatewayErrorCode.deviceOffline) {
                status = Status.offline
            } else {
                status = Status.failed
                error = ErrorText.text(failure)
            }
        }
    }
}
