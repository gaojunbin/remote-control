package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.protocol.AppFrame
import kotlinx.serialization.Serializable

// What `GatewaySocket` reports, split from `GatewaySocket.swift` for its length.

@Serializable
enum class ConnectionState(val rawValue: String) {
    idle("idle"), connecting("connecting"), connected("connected"), reconnecting("reconnecting"),
    unauthorized("unauthorized"), disconnected("disconnected");

    companion object {
        operator fun invoke(rawValue: String): ConnectionState? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * Why the gateway closed the socket, and therefore what to do next.
 *
 * Amendment A4: 4401 and 4403 are terminal for this session; 4001 means this connection was
 * replaced and reconnecting would just fight the replacement; anything else is transient and
 * earns a backoff.
 */
enum class SocketCloseReason {
    unauthorized, forbidden, replaced, transient;

    /** Only a transient close is worth another attempt. */
    val shouldReconnect: Boolean get() = this == transient

    companion object {
        const val unauthorizedCode = 4401
        const val forbiddenCode = 4403
        const val replacedCode = 4001

        operator fun invoke(code: Int?): SocketCloseReason = when (code) {
            unauthorizedCode -> unauthorized
            forbiddenCode -> forbidden
            replacedCode -> replaced
            else -> transient
        }
    }
}

sealed interface GatewayEvent {
    data class State(val state: ConnectionState) : GatewayEvent
    data class Frame(val frame: AppFrame) : GatewayEvent

    /** The socket closed for a reason the app must act on. */
    data class Closed(val reason: SocketCloseReason) : GatewayEvent

    /**
     * A request left the app but no reply arrived before the socket dropped. The app shows
     * "delivery unconfirmed" and never resends on its own.
     */
    data class RequestUncertain(val id: String) : GatewayEvent
    data class Failure(val error: TransportError) : GatewayEvent
}
