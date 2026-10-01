package com.junbingao.remotecontrol.android.screens.alerts

import android.content.Context
import com.junbingao.remotecontrol.android.push.LocalNotifications
import com.junbingao.remotecontrol.android.push.PushAuthorization
import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.TurnAlerts

/**
 * Placeholder for `android-settings`: the iPhone's `TurnNotifier`. On Android it posts a system
 * notification from the live connection at the moments the gateway pushes for, skipped while that
 * conversation is on screen (`docs/DESIGN.md` § "The Android app") — so where the iPhone's gates
 * on the app being in front, this one is told whether the conversation is on screen ([onScreen]).
 *
 * The model calls the two [announce]s; the port may replace their bodies, keeping these
 * signatures. What they do now is the rule as DESIGN states it: three gates, then one
 * notification through `LocalNotifications`, whose tap opens the conversation.
 */
class TurnNotifier(private val context: Context) {
    /** The last notification raised, for previews and checks to read back. */
    var lastAlert: TurnAlert? = null
        private set

    fun announce(previous: Session, current: Session, deviceName: String, onScreen: Boolean, enabled: Boolean,
                 authorization: PushAuthorization): TurnAlert? {
        val kind = TurnAlerts.kind(previous = previous, current = current) ?: return null
        return announce(kind = kind, session = current, deviceName = deviceName, onScreen = onScreen, enabled = enabled,
                        authorization = authorization)
    }

    /** The same gates for news the state table does not produce: amendment A35's pause, resume and drop. */
    fun announce(kind: PushKind, session: Session, deviceName: String, identifier: String? = null, onScreen: Boolean,
                 enabled: Boolean, authorization: PushAuthorization): TurnAlert? {
        if (onScreen || !enabled || !authorization.raisesBanners) return null
        val alert = TurnAlert(kind = kind, deviceName = deviceName, session = session, identifier = identifier)
        lastAlert = alert
        val link = alert.route.deepLink ?: return alert
        LocalNotifications.post(context, kind = kind.rawValue, identifier = alert.identifier, title = alert.title,
                                body = alert.body, thread = alert.threadIdentifier, deepLink = link.toString())
        return alert
    }
}
