package com.junbingao.remotecontrol.win.platform

/** The conversation a notification names, and the one a click on it opens. */
data class ToastTarget(val deviceId: String, val sessionId: String)

/**
 * Which conversation a click in the notification area opens. Windows reports a click on the
 * app's notification and a double click on its icon the same way — an action on the icon — so
 * the two are told apart by the press on the icon that comes before a double click and never
 * before a click on a notification. A notification opens its conversation once; after that, and
 * with nothing posted, an action opens the window.
 */
class ToastClicks(private val now: () -> Long = System::currentTimeMillis) {
    private var posted: ToastTarget? = null
    private var pressedAt = Long.MIN_VALUE / 2

    /** A notification was shown; a newer one takes the place of the last, as Windows shows one at a time. */
    fun posted(target: ToastTarget) {
        posted = target
    }

    /** The pointer pressed the icon itself. */
    fun iconPressed() {
        pressedAt = now()
    }

    /** The notifications went (sign out): a click can no longer open one. */
    fun cleared() {
        posted = null
    }

    /** An action on the icon: the conversation a clicked notification names, or null to open the window. */
    fun action(): ToastTarget? {
        if (now() - pressedAt < DOUBLE_CLICK_MILLIS) return null
        return posted.also { posted = null }
    }

    companion object {
        /** Longer than Windows' double-click time, which is 500 ms unless someone changed it. */
        const val DOUBLE_CLICK_MILLIS = 800L
    }
}
