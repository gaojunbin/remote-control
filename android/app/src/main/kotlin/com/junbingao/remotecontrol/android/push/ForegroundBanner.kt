package com.junbingao.remotecontrol.android.push

/**
 * Whether a notification arriving while the app is open is shown.
 *
 * The app announces a transition itself the moment it arrives on the socket, so the gateway's
 * push for the same transition would be a second banner for one event. It is dropped while the
 * app is in the foreground and connected, and shown in every other case. A local alert the app
 * raised is always shown (`SystemTurnAlerts.swift`). Android has no push channel yet
 * (`docs/DESIGN.md` § "The Android app"), so every notification here is local; the rule is kept
 * for the day one arrives.
 */
object ForegroundBanner {
    fun shows(remote: Boolean, suppressesRemote: Boolean): Boolean = !(remote && suppressesRemote)
}
