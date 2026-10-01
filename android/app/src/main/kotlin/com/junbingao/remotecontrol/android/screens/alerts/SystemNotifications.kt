package com.junbingao.remotecontrol.android.screens.alerts

import android.content.Context
import com.junbingao.remotecontrol.android.push.LocalNotifications
import com.junbingao.remotecontrol.android.push.NotificationAuthorization
import com.junbingao.remotecontrol.android.push.PushAuthorization

/**
 * Android's notifications, and nothing else.
 *
 * The iPhone's counterpart also holds the APNs token for the process and hands a tapped
 * notification to the model. Neither is here: Android has no push channel yet (`docs/DESIGN.md`
 * § "The Android app"), and a tap is a `remotecontrol://session` link, which `MainActivity` hands
 * to the model itself.
 */
class SystemNotifications(private val context: Context) : NotificationPlatform {
    override val supported: Boolean = true

    override suspend fun authorization(): PushAuthorization = NotificationAuthorization.status(context)

    override fun unregister() = LocalNotifications.removeAll(context)

    override fun openSettings() = NotificationAuthorization.openSettings(context)
}
