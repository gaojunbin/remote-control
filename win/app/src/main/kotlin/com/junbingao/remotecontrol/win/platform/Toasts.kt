package com.junbingao.remotecontrol.win.platform

import java.awt.TrayIcon

/** One notification: the push the gateway would have sent, raised by the app itself. */
data class ToastNotice(val title: String, val body: String, val target: ToastTarget)

/**
 * Where the app's notifications go (`docs/DESIGN.md` § "The Windows app" → **Notify me posts from
 * the app**): Windows' notifications in the app, and a stand-in wherever there are none. The
 * notifier that decides what to post talks to this and nothing else, so its rules are checked
 * without posting anything. Windows asks nobody's permission for these: a person turns them off
 * in Windows Settings, which the app cannot read, so `post` is all there is.
 */
interface Toasts {
    fun post(notice: ToastNotice)

    /** What this app posted and the person has not dismissed: after this, a click opens nothing. */
    fun removeDelivered()

    /** A notification the person clicked. */
    var onOpen: ((ToastTarget) -> Unit)?
}

/**
 * The toasts of a process that is not the app, or not on Windows — the renderer, the test runner,
 * a run on a Mac: everything it is told is kept, and nothing leaves the process.
 */
class InertToasts : Toasts {
    private val kept = mutableListOf<ToastNotice>()
    val posted: List<ToastNotice> get() = kept.toList()
    override var onOpen: ((ToastTarget) -> Unit)? = null

    override fun post(notice: ToastNotice) {
        kept += notice
    }

    override fun removeDelivered() {
        kept.clear()
    }
}

/**
 * Windows' notifications, shown from the app's icon in the notification area: Windows 10 and 11
 * draw them as toasts under the app's name and keep them in the notification centre. Windows shows
 * one of an icon's notifications at a time, so a newer one takes the older one's place, as the
 * web's `tag` has it; a click on one arrives as an action on the icon (`ToastClicks`).
 */
class TrayToasts(private val tray: AppTray) : Toasts {
    override var onOpen: ((ToastTarget) -> Unit)? = null

    override fun post(notice: ToastNotice) {
        tray.clicks.posted(notice.target)
        tray.icon.displayMessage(notice.title, notice.body, TrayIcon.MessageType.NONE)
    }

    override fun removeDelivered() {
        tray.clicks.cleared()
    }

    companion object {
        /** Windows' notifications in the app on Windows, the inert stand-in everywhere else. */
        fun make(tray: AppTray?): Toasts = if (Host.isWindows && tray != null) TrayToasts(tray) else InertToasts()
    }
}
