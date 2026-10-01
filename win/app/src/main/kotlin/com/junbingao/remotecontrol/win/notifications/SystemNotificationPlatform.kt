package com.junbingao.remotecontrol.win.notifications

import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.platform.Host
import com.junbingao.remotecontrol.win.platform.ToastNotice
import com.junbingao.remotecontrol.win.platform.ToastTarget
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.lang.ref.WeakReference

/**
 * Windows' notifications, for the app itself: the toasts the window's icon in the notification
 * area shows (the model's `toasts`). Windows has no push channel of this app's own — the gateway's
 * APNs topic is the iPhone app's — so the running app posts what the gateway would have pushed
 * (`docs/DESIGN.md` § "The Windows app" → **Notify me posts from the app**).
 *
 * Windows asks nobody before an app's notifications show: they are on until the person turns them
 * off in Windows Settings, which is the one thing read here, so turning the switch on asks Windows
 * nothing it would show. A click is the window's: the model's toasts bring it forward on the
 * conversation the notice names.
 */
class SystemNotificationPlatform(model: WinAppModel) : NotificationPlatform {
    private val model = WeakReference(model)

    override suspend fun permission(): NotificationPermission =
        if (WindowsNotifications.areOff()) NotificationPermission.denied else NotificationPermission.authorized

    override suspend fun requestPermission(): NotificationPermission = permission()

    override fun post(notice: TurnNotice) {
        val target = ToastTarget(deviceId = notice.target.deviceID, sessionId = notice.target.sessionID)
        model.get()?.toasts?.post(ToastNotice(title = notice.title, body = notice.body, target = target))
    }

    override fun removeDelivered() {
        model.get()?.toasts?.removeDelivered()
    }
}

/**
 * Windows Settings' switch for every app's notifications (System → Notifications), which Windows
 * keeps as `ToastEnabled` under the current user's push notification settings: zero is off, and
 * no value at all is Windows' own default, on. A machine whose registry cannot be read is taken
 * at that default rather than reported as refusing.
 */
private object WindowsNotifications {
    private const val key = "Software\\Microsoft\\Windows\\CurrentVersion\\PushNotifications"
    private const val value = "ToastEnabled"

    fun areOff(): Boolean {
        if (!Host.isWindows) return false
        return runCatching {
            Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, value) &&
                Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, key, value) == 0
        }.getOrDefault(false)
    }
}
