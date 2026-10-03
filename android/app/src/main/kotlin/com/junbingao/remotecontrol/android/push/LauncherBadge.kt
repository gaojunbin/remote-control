package com.junbingao.remotecontrol.android.push

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.junbingao.remotecontrol.R
import com.junbingao.remotecontrol.android.MainActivity
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.strings.L10n

/**
 * The app icon's badge (`docs/DESIGN.md` § "A red dot for a session that stopped and waits for
 * you"): Android draws a launcher badge from an app's notifications, so while any session has a red
 * dot the app keeps one quiet notification whose number is the count — launchers that show numbers
 * show it, the others a dot. It posts into a channel of its own, which makes no sound and never
 * comes down over the screen, and a tap opens Sessions.
 *
 * Like every notification the app posts, it needs the permission Notify me asks for; without it
 * nothing is posted and there is no badge.
 */
object LauncherBadge {
    /** Its channel, apart from the kinds of news (`NotificationChannels`), so silencing those keeps the badge. */
    const val channelId = "rc.badge"

    /** What the one notification is filed under, so posting again replaces it and nothing else takes it down. */
    private const val tag = "rc.badge"

    /** Post the count, or replace the one already posted. Returns whether it was posted: nothing is while notifications are not allowed. */
    @SuppressLint("MissingPermission") // `NotificationAuthorization` is asked on the line above.
    fun post(context: Context, count: Int): Boolean {
        if (NotificationAuthorization.status(context) != PushAuthorization.authorized) return false
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(sentence(count))
            .setNumber(count)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(opening(context))
            .build()
        NotificationManagerCompat.from(context).notify(tag, 0, notification)
        return true
    }

    fun remove(context: Context) {
        NotificationManagerCompat.from(context).cancel(tag, 0)
    }

    /** "2 sessions are waiting for you", in the interface language. */
    fun sentence(count: Int): String =
        if (count == 1) L10n.string("1 session is waiting for you") else L10n.string("%lld sessions are waiting for you", count)

    /**
     * Its channel, named in the interface language and renamed in place when that changes, as the
     * other channels are. Low importance is Android's quiet: no sound, no heads-up, still a badge.
     */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(L10n.string("Sessions"))
            .setShowBadge(true)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** The tap: the app, on Sessions. */
    private fun opening(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(LaunchOptions.OPENS_SESSIONS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, tag.hashCode(), intent,
                                         PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
