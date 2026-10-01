package com.junbingao.remotecontrol.win.settings

import com.junbingao.remotecontrol.core.state.ConnectionPhase
import com.junbingao.remotecontrol.core.state.DotTone
import com.junbingao.remotecontrol.win.strings.S

/**
 * `connectionTone.ts`: the dot in the Settings header, from the app socket alone
 * (`docs/DESIGN.md` § "The Settings screen") — green while the socket is open, which is the web's
 * `open` and includes the moment before `hello` lands; pulsing amber while it is being made or
 * remade; grey while it is down. The ruling's red for a refused gateway never reaches a browser,
 * which the gateway signs out instead, but it does reach this app: a connection another app took
 * over, or one speaking a protocol this build does not, stays on screen until the person acts.
 */
object IdentityDot {
    fun tone(phase: ConnectionPhase): DotTone = when (phase) {
        ConnectionPhase.Syncing, ConnectionPhase.Connected -> DotTone.working
        ConnectionPhase.Connecting, ConnectionPhase.Reconnecting -> DotTone.waiting
        ConnectionPhase.SignedOut -> DotTone.off
        ConnectionPhase.Expired, ConnectionPhase.Forbidden, ConnectionPhase.Superseded, is ConnectionPhase.Incompatible -> DotTone.failed
    }

    /** The word for that tone. It is read aloud and shown on hover, never printed. */
    fun word(phase: ConnectionPhase): String = when (tone(phase)) {
        DotTone.working -> S.settings.connected
        DotTone.waiting -> S.settings.connecting
        DotTone.failed -> S.winSettings.refused
        else -> S.settings.offline
    }
}
