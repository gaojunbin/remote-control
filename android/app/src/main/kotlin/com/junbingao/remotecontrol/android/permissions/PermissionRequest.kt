package com.junbingao.remotecontrol.android.permissions

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

/**
 * One runtime permission, asked for at the moment the iPhone asks for its counterpart
 * (`docs/DESIGN.md` § "The Android app": permissions are asked as Android asks them, at the
 * moment the iPhone asks) — the camera when a scan or a photo starts, the microphone when
 * dictation does, notifications when their switch is turned on.
 *
 * Nothing asks ahead of that moment, and a permission the app already holds asks nothing.
 */
interface PermissionRequest {
    /** Whether the app holds the permission now. Reading it never shows a prompt. */
    fun isGranted(): Boolean

    /**
     * The person's answer: at once when the permission is already held, otherwise after the
     * system's own prompt. A second call while a prompt is up waits for the same answer rather
     * than stacking a second prompt.
     */
    suspend fun request(): Boolean
}

/**
 * The request for [permission], bound to the screen that asks — Android delivers the answer to
 * the activity, so the launcher has to be registered by the composable that will ask.
 */
@Composable
fun rememberPermissionRequest(permission: String): PermissionRequest {
    val context = LocalContext.current
    val answer = remember(permission) { PendingAnswer() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        answer.resolve(granted)
    }
    return remember(permission, launcher) { LauncherPermissionRequest(context, permission, launcher, answer) }
}

/** Whether [permission] is held, for code that has a context and no screen. */
fun isPermissionGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private class LauncherPermissionRequest(
    private val context: Context,
    private val permission: String,
    private val launcher: ManagedActivityResultLauncher<String, Boolean>,
    private val answer: PendingAnswer,
) : PermissionRequest {
    override fun isGranted(): Boolean = isPermissionGranted(context, permission)

    override suspend fun request(): Boolean {
        if (isGranted()) return true
        val (waiting, first) = answer.await()
        if (first) launcher.launch(permission)
        return waiting.await()
    }
}

/** The one prompt that is up, if any, and the answer everyone asking is waiting for. */
internal class PendingAnswer {
    private var pending: CompletableDeferred<Boolean>? = null

    /** The answer to wait for, and whether this caller is the one who has to show the prompt. */
    fun await(): Pair<CompletableDeferred<Boolean>, Boolean> {
        pending?.let { return it to false }
        val fresh = CompletableDeferred<Boolean>()
        pending = fresh
        return fresh to true
    }

    fun resolve(granted: Boolean) {
        val waiting = pending
        pending = null
        waiting?.complete(granted)
    }
}
