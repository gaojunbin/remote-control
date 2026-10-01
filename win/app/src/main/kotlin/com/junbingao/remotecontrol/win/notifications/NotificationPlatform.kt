package com.junbingao.remotecontrol.win.notifications

import com.junbingao.remotecontrol.win.app.WinAppModel

/**
 * What this PC lets the app do with notifications, in the three answers the Notify me row tells
 * apart: not asked yet, turned off in Windows Settings, and allowed. Windows asks nobody before an
 * app's notifications show, so Windows' own answer is one of the last two; the first is the one
 * the switch's rule asks about, as the Mac's does.
 */
enum class NotificationPermission { notDetermined, denied, authorized }

/** The session a notification names, and the one a click on it opens. */
data class NoticeTarget(val deviceID: String, val sessionID: String)

/**
 * Where notifications go: Windows' notifications in the app, and a stand-in wherever they must not
 * go. The notifier talks to this and nothing else, so its rules are checked without posting
 * anything. A click on one is the window's: the model's `toasts` bring it forward on the
 * conversation the notice names.
 */
interface NotificationPlatform {
    suspend fun permission(): NotificationPermission

    /** Asks again, from the Notify me switch and from nowhere else. */
    suspend fun requestPermission(): NotificationPermission

    fun post(notice: TurnNotice)

    /** What this app has posted and the person has not dismissed. */
    fun removeDelivered()
}

/**
 * The platform of a run that must never post — the renderer, the test runner and every other
 * ephemeral run. It never asks anyone anything: everything it is told is kept, and nothing leaves
 * the process.
 */
class InertNotificationPlatform(
    /** What `permission()` answers. Allowed by default, so a render of the switch reads what the setting holds. */
    var granted: NotificationPermission = NotificationPermission.authorized,
    /** What the question would be answered with. */
    var answer: NotificationPermission = NotificationPermission.authorized,
) : NotificationPlatform {
    var posted: List<TurnNotice> = emptyList()
        private set
    var requests = 0
        private set

    override suspend fun permission(): NotificationPermission = granted

    override suspend fun requestPermission(): NotificationPermission {
        requests += 1
        granted = answer
        return granted
    }

    override fun post(notice: TurnNotice) {
        posted = posted + notice
    }

    override fun removeDelivered() {
        posted = emptyList()
    }
}

object NotificationPlatforms {
    /**
     * Windows' notifications for a run that keeps the person's things, and the inert stand-in for
     * an ephemeral one: the renderer, the tests and every automated run are ephemeral, and none of
     * them may ever post a notification on the machine it runs on.
     */
    fun make(model: WinAppModel): NotificationPlatform =
        if (model.options.ephemeral) InertNotificationPlatform() else SystemNotificationPlatform(model)
}
