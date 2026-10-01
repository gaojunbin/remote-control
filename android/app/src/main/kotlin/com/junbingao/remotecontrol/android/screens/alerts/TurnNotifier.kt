package com.junbingao.remotecontrol.android.screens.alerts

import android.content.Context
import com.junbingao.remotecontrol.android.push.PushAuthorization
import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.TurnAlerts

/**
 * The posting side of a notification, so a check drives the rule without Android's notification
 * manager — the same shape as [NotificationPlatform].
 */
interface TurnAlertPlatform {
    fun post(alert: TurnAlert)
}

/**
 * Raises a notification for a finished or waiting turn.
 *
 * Three gates, all of which must hold: the conversation is not on screen, the Notify me switch is
 * on, and the system has granted permission. Where the iPhone gates on the app being in front,
 * because the gateway's push reaches a locked phone, Android has no push channel yet: the app posts
 * from its live connection, and skips only what the reader is already looking at (`docs/DESIGN.md`
 * § "The Android app"). The transition table itself is `TurnAlerts` in the core, which is the
 * gateway's.
 */
class TurnNotifier(private val platform: TurnAlertPlatform) {
    constructor(context: Context) : this(SystemTurnAlerts(context.applicationContext))

    /** The last notification raised, for pictures and checks to read back. */
    var lastAlert: TurnAlert? = null
        private set

    fun announce(previous: Session, current: Session, deviceName: String, onScreen: Boolean, enabled: Boolean,
                 authorization: PushAuthorization): TurnAlert? {
        val kind = TurnAlerts.kind(previous = previous, current = current) ?: return null
        return announce(kind = kind, session = current, deviceName = deviceName, onScreen = onScreen, enabled = enabled,
                        authorization = authorization)
    }

    /**
     * The same three gates for news the state table does not produce: amendment A35's pause,
     * resume and drop, which are `resume` events rather than transitions.
     */
    fun announce(kind: PushKind, session: Session, deviceName: String, identifier: String? = null, onScreen: Boolean,
                 enabled: Boolean, authorization: PushAuthorization): TurnAlert? {
        if (onScreen || !enabled || !authorization.raisesBanners) return null
        val alert = TurnAlert(kind = kind, deviceName = deviceName, session = session, identifier = identifier)
        lastAlert = alert
        platform.post(alert)
        return alert
    }
}
