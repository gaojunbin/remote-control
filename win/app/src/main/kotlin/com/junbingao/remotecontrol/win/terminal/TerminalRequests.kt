package com.junbingao.remotecontrol.win.terminal

import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.state.GatewayChannel
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement

/**
 * The channel the core's `TerminalSession` sends through: the connection's own socket, whichever
 * one it is at the moment a request leaves — a sign-in or a take-over makes a new one, and a
 * terminal page outlives neither — with the error of the last refused request kept as the gateway
 * sent it.
 *
 * `TerminalSession` words a refusal in the iPhone app's sentences before any screen sees it; this
 * page says the web's instead (`errorText` in `lib/errors.ts`, chosen by `error.code`), so it keeps
 * the error itself, as the login page's `SignInRecorder` does for a sign-in.
 */
class TerminalRequests(private val socket: () -> GatewayChannel?, private val failures: TerminalFailures) : GatewayChannel {
    /** `TerminalSession` sends requests and reads nothing else; the frames it is fed come from the connection's own frame handlers. */
    override val events: Flow<GatewayEvent> = emptyFlow()

    override suspend fun connect() {}

    override suspend fun disconnect() {}

    override suspend fun request(request: GatewayRequest): JsonElement {
        val channel = socket()
        if (channel == null) {
            failures.record(TransportError.NotConnected)
            throw TransportError.NotConnected
        }
        try {
            return channel.request(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failures.record(error)
            throw error
        }
    }
}

/** The last error a terminal request ended with. */
class TerminalFailures {
    @Volatile
    var latest: Throwable? = null
        private set

    fun record(error: Throwable) {
        latest = error
    }
}
