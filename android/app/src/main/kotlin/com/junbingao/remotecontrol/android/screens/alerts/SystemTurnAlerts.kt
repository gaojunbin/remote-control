package com.junbingao.remotecontrol.android.screens.alerts

import android.content.Context
import com.junbingao.remotecontrol.android.push.LocalNotifications

/**
 * Hands one notification to the system, and nothing else.
 *
 * The app asked for permission once, through [PushController], and this reuses it: a notification
 * the app raises for itself and the switch in Settings are one switch and one system permission
 * (`docs/DESIGN.md` § "Being told when a turn ends"). The tap is the session's own link, which is
 * the path a link from anywhere else takes.
 */
class SystemTurnAlerts(private val context: Context) : TurnAlertPlatform {
    override fun post(alert: TurnAlert) {
        val link = alert.route.deepLink ?: return
        LocalNotifications.post(
            context,
            kind = alert.route.kind.rawValue,
            identifier = alert.identifier,
            title = alert.title,
            body = alert.body,
            thread = alert.threadIdentifier,
            deepLink = link.toString(),
        )
    }
}
