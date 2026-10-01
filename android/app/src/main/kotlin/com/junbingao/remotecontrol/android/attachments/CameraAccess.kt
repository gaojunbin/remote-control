package com.junbingao.remotecontrol.android.attachments

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.junbingao.remotecontrol.android.permissions.AppSettings
import com.junbingao.remotecontrol.android.permissions.PermissionRequest
import com.junbingao.remotecontrol.android.permissions.rememberPermissionRequest

/**
 * Whether the app may use the camera.
 *
 * `docs/DESIGN.md` § "The three screens" → **A camera the app may not use says so** puts every
 * refusal on the same line — refused once, refused for good, blocked by a policy — so there are
 * two answers here and not four.
 */
enum class CameraAccess { allowed, denied }

/** The camera the app asks for, and whether there is one at all. */
object Camera {
    /**
     * Whether this phone has a camera to offer. Nothing offers the camera item, or the scanner's
     * viewfinder, before asking this.
     */
    fun exists(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /** The app's own page in Android Settings, where a refused camera is the only thing left. */
    fun openSystemSettings(context: Context) = AppSettings.open(context)
}

/**
 * `Camera.requestAccess()`: the person is asked the first time, and everything that is not an
 * explicit yes is a refusal. Bound to the screen that asks, because Android answers the activity.
 */
class CameraAccessRequest internal constructor(private val permission: PermissionRequest) {
    /** Whether the camera may be opened now; reading it asks nothing. */
    val isAllowed: Boolean get() = permission.isGranted()

    suspend fun request(): CameraAccess = if (permission.request()) CameraAccess.allowed else CameraAccess.denied
}

@Composable
fun rememberCameraAccessRequest(): CameraAccessRequest {
    val permission = rememberPermissionRequest(Manifest.permission.CAMERA)
    return remember(permission) { CameraAccessRequest(permission) }
}
