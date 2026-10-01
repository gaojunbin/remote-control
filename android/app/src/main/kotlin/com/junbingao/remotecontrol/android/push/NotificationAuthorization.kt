package com.junbingao.remotecontrol.android.push

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.junbingao.remotecontrol.android.permissions.AppSettings
import com.junbingao.remotecontrol.android.permissions.PermissionRequest
import com.junbingao.remotecontrol.android.permissions.isPermissionGranted
import com.junbingao.remotecontrol.android.permissions.rememberPermissionRequest

/**
 * Whether the app may post, asked when the Notifications switch is turned on and at no other
 * moment — the counterpart of `UNUserNotificationCenter`'s authorization in `SystemNotifications`.
 *
 * Android 13 and later ask with a runtime permission; below that notifications are allowed until
 * the person turns them off in Settings. A refusal is final to the app on both: only Settings can
 * change it, which is where [openSettings] goes.
 */
object NotificationAuthorization {
    private const val FILE = "notifications"
    private const val ASKED = "asked"

    fun status(context: Context): PushAuthorization {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return if (enabled) PushAuthorization.authorized else PushAuthorization.denied
        }
        return when {
            isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS) && enabled -> PushAuthorization.authorized
            isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS) -> PushAuthorization.denied
            asked(context) -> PushAuthorization.denied
            else -> PushAuthorization.notDetermined
        }
    }

    /** The app's notification settings, where a refusal is undone. */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            AppSettings.open(context)
        }
    }

    internal fun asked(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    internal fun markAsked(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(ASKED, true) }
    }
}

/**
 * Asking for notifications: `requestAuthorization`. Below Android 13 there is nothing to ask and
 * the answer is what Settings says.
 */
class NotificationPermissionRequest internal constructor(
    private val context: Context,
    private val permission: PermissionRequest,
) {
    suspend fun request(): PushAuthorization {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            NotificationAuthorization.status(context) == PushAuthorization.notDetermined
        ) {
            permission.request()
            NotificationAuthorization.markAsked(context)
        }
        return NotificationAuthorization.status(context)
    }
}

// The permission's name is only a string below Android 13, where nothing asks for it.
@SuppressLint("InlinedApi")
@Composable
fun rememberNotificationPermissionRequest(): NotificationPermissionRequest {
    val context = LocalContext.current
    val permission = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS)
    return remember(permission) { NotificationPermissionRequest(context.applicationContext, permission) }
}
