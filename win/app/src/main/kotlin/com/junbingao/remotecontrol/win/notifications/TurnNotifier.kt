package com.junbingao.remotecontrol.win.notifications

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.device
import java.lang.ref.WeakReference

/**
 * This PC's notifications (`docs/DESIGN.md` § "The Windows app" → **Notify me posts from the
 * app**): the Notify me switch's two halves — the setting, which is this PC's and not the
 * account's (A41 keeps `notificationsEnabled` on the device), and Windows' own — and the posting
 * itself, at the moments the gateway pushes for. A click on what was posted is the window's: the
 * model's `toasts` open the conversation it names.
 */
class TurnNotifier(model: WinAppModel, val platform: NotificationPlatform) {
    var permission: NotificationPermission by mutableStateOf(NotificationPermission.notDetermined)
        private set

    /** True while the question is out; the switch waits for it. */
    var isAsking: Boolean by mutableStateOf(false)
        private set

    private val reference = WeakReference(model)
    private val model: WinAppModel? get() = reference.get()

    /** What the switch shows: on only while the setting is on and Windows lets the app post. */
    val isOn: Boolean
        get() = (model?.settings?.notificationsEnabled ?: false) && permission == NotificationPermission.authorized

    /** Ask Windows again: the person may have changed it in Windows Settings while the window was behind another. */
    suspend fun refresh() {
        permission = platform.permission()
    }

    /** The switch. Turning it on is the one thing in the app that asks, and only while nothing has been asked yet. */
    suspend fun turn(on: Boolean) {
        val settings = model?.settings ?: return
        if (!on) {
            settings.notificationsEnabled = false
            return
        }
        refresh()
        if (permission == NotificationPermission.notDetermined) {
            isAsking = true
            permission = platform.requestPermission()
            isAsking = false
        }
        settings.notificationsEnabled = permission == NotificationPermission.authorized
    }

    // Posting

    /** A session the app already knew arrived in a newer version. */
    fun sessionChanged(from: Session, to: Session) {
        for (kind in TurnNoticeRule.kinds(previous = from, current = to)) announce(kind, about = to)
    }

    /** A frame on the app socket: a `resume` event that ran or gave up. */
    fun receive(frame: AppFrame) {
        if (frame !is AppFrame.SessionEvent) return
        val resume = frame.event.resume ?: return
        val kind = TurnNoticeRule.kind(resume = resume.status) ?: return
        val session = model?.connection?.sessions?.firstOrNull {
            it.sessionID == frame.sessionID && (frame.deviceID == null || it.deviceID == frame.deviceID)
        } ?: return
        announce(kind, about = session)
    }

    private fun announce(kind: PushKind, about: Session) {
        val model = model ?: return
        val target = NoticeTarget(deviceID = about.deviceID, sessionID = about.sessionID)
        val posts = TurnNoticeRule.posts(
            target, enabled = model.settings.notificationsEnabled, permission = permission,
            route = model.router.route, windowActive = model.isWindowActive,
        )
        if (!posts) return
        // `device_name` falls back to the id, as the gateway's own lookup does.
        val name = model.device(about.deviceID)?.name ?: about.deviceID
        platform.post(TurnNotice(kind = kind, target = target, deviceName = name))
    }

    /** Sign-out: what was posted names the account's machines and sessions. */
    fun signedOut() {
        platform.removeDelivered()
    }
}
