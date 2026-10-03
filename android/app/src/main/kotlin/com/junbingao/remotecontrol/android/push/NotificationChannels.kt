package com.junbingao.remotecontrol.android.push

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.junbingao.remotecontrol.android.strings.L10n

/**
 * One notification channel per kind of news the gateway pushes for (`PushKind`), so a person can
 * silence one kind in Android Settings and keep the others — the counterpart of the iPhone's
 * single switch plus its per-app notification settings.
 *
 * Each channel is named with the iPhone's alert word for its kind (`PushKind.alertWord` in
 * `TurnNotifier.swift`), in the interface language, and renamed when the language changes. Every
 * kind interrupts with a banner and a sound, as each one does on the iPhone. None of them counts on
 * the app's icon: a launcher that shows numbers adds up every notification it may badge, so a "Turn
 * finished" beside the badge's own notification would count one session twice. The icon's number is
 * [LauncherBadge]'s alone (A47).
 */
object NotificationChannels {
    /** A `PushKind` raw value and the catalogue key of its word. */
    data class Kind(val rawValue: String, val word: String)

    val kinds = listOf(
        Kind("turn_completed", "Turn finished"),
        Kind("needs_approval", "Needs your approval"),
        Kind("needs_input", "Waiting for your answer"),
        Kind("error", "Errored"),
        Kind("limit_reached", "Paused by the usage limit"),
        Kind("resumed", "Resumed after the limit reset"),
        Kind("resume_dropped", "Not resumed"),
    )

    /** The channel a kind posts into. A kind this build has never met gets one of its own. */
    fun channelId(kind: String): String = "rc.$kind"

    /** The word a kind is announced with; a kind this build has never met reads as itself. */
    fun word(kind: String): String = kinds.firstOrNull { it.rawValue == kind }?.let { L10n.string(it.word) } ?: kind

    /**
     * Create every channel, or rename it in place: Android keeps a channel's settings under its
     * id, so calling this again on a change of language keeps what the person chose for each.
     */
    fun ensure(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannelsCompat(kinds.map { channel(it.rawValue, L10n.string(it.word)) })
    }

    internal fun ensure(context: Context, kind: String) {
        if (kinds.any { it.rawValue == kind }) return ensure(context)
        NotificationManagerCompat.from(context).createNotificationChannel(channel(kind, kind))
    }

    private fun channel(kind: String, name: String) =
        NotificationChannelCompat.Builder(channelId(kind), NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(name)
            .setShowBadge(false)
            .build()
}
