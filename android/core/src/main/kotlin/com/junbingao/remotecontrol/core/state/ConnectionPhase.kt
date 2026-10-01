package com.junbingao.remotecontrol.core.state

// From RCCore's `ConnectionStore.swift`, split for the store's length.

/**
 * How the app describes its own connection. Each case is distinguishable in the UI, because "the
 * gateway is unreachable" and "your Mac is offline" call for different actions.
 */
sealed interface ConnectionPhase {
    data object SignedOut : ConnectionPhase
    data object Connecting : ConnectionPhase
    data object Syncing : ConnectionPhase
    data object Connected : ConnectionPhase
    data object Reconnecting : ConnectionPhase
    data object Expired : ConnectionPhase
    data object Forbidden : ConnectionPhase

    /** Another connection replaced this one; reconnecting on our own would just fight it, so the user decides. */
    data object Superseded : ConnectionPhase
    data class Incompatible(val gatewayVersion: Int) : ConnectionPhase

    /**
     * Whether a request issued now can still reach the gateway. A socket that is connecting or
     * coming back does: the transport holds the request until the hello lands, so the composer
     * stays live across a reconnect. A session that ended, expired or was replaced does not.
     */
    val canReachGateway: Boolean
        get() = when (this) {
            Connecting, Syncing, Connected, Reconnecting -> true
            SignedOut, Expired, Forbidden, Superseded, is Incompatible -> false
        }
}
