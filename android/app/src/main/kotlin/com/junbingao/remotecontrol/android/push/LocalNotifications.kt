package com.junbingao.remotecontrol.android.push

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.junbingao.remotecontrol.R
import com.junbingao.remotecontrol.android.MainActivity

/**
 * Posts one notification the app raises for itself: the counterpart of `SystemTurnAlerts`.
 * `docs/DESIGN.md` § "The Android app": until Android has a push channel the app posts from its
 * live connection at the moments the gateway pushes for, with the push's words, and a tap opens
 * the conversation.
 *
 * The title is the device's name and the body the status word — no prompt text, no output, no
 * file name — as `TurnAlert` builds them. The gates (the Notifications switch, the conversation
 * not being on screen) are the caller's, as `TurnNotifier`'s are on the iPhone.
 */
object LocalNotifications {
    /**
     * Post it now. [identifier] is what the system deduplicates on, as the iPhone's request
     * identifier is; [thread] groups a session's notifications under it, as `threadIdentifier`
     * does; [deepLink] is `remotecontrol://session?device=…&id=…`, opened by a tap. Returns
     * whether it was posted: nothing is while notifications are not allowed.
     */
    @SuppressLint("MissingPermission") // `NotificationAuthorization` is asked on the line above.
    fun post(context: Context, kind: String, identifier: String, title: String, body: String,
             thread: String, deepLink: String): Boolean {
        if (NotificationAuthorization.status(context) != PushAuthorization.authorized) return false
        NotificationChannels.ensure(context, kind)
        val notification = NotificationCompat.Builder(context, NotificationChannels.channelId(kind))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setGroup(thread)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(opening(context, identifier, deepLink))
            .build()
        NotificationManagerCompat.from(context).notify(identifier, 0, notification)
        return true
    }

    /** Take every delivered notification down, as signing out or turning the switch off does. */
    fun removeAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    /** The tap: the app's own deep link, handed to `MainActivity` as a VIEW intent. */
    internal fun opening(context: Context, identifier: String, deepLink: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, deepLink.toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, identifier.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
