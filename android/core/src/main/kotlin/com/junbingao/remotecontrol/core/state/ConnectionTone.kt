package com.junbingao.remotecontrol.core.state

/**
 * The dot beside the gateway in the Settings header, and the word for it.
 *
 * `docs/DESIGN.md` § "The Settings screen": green while connected, pulsing amber while the link is
 * being made or remade, grey while it is down, red when the gateway refused it. The word —
 * Connected, Connecting, Offline, Refused — is the accessibility label and nothing else: the dot
 * carries the state on screen, exactly as it does on a session row.
 *
 * A pure function of the phase, so the header never decides this for itself.
 */
object ConnectionTone {
    fun dot(phase: ConnectionPhase): DotTone = when (phase) {
        ConnectionPhase.Connected -> DotTone.working
        ConnectionPhase.Connecting, ConnectionPhase.Syncing, ConnectionPhase.Reconnecting -> DotTone.waiting
        ConnectionPhase.SignedOut -> DotTone.off
        ConnectionPhase.Expired, ConnectionPhase.Forbidden, ConnectionPhase.Superseded,
        is ConnectionPhase.Incompatible -> DotTone.failed
    }

    fun word(phase: ConnectionPhase): String = when (phase) {
        ConnectionPhase.Connected -> L10n.string("Connected")
        ConnectionPhase.Connecting, ConnectionPhase.Syncing, ConnectionPhase.Reconnecting -> L10n.string("Connecting")
        ConnectionPhase.SignedOut -> L10n.string("Offline")
        ConnectionPhase.Expired, ConnectionPhase.Forbidden, ConnectionPhase.Superseded,
        is ConnectionPhase.Incompatible -> L10n.string("Refused")
    }
}
