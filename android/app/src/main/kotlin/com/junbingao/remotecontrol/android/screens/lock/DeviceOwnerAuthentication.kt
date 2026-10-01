package com.junbingao.remotecontrol.android.screens.lock

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.fragment.app.FragmentActivity
import com.junbingao.remotecontrol.android.security.BiometricLock

/**
 * The question the lock puts to the phone's owner: the iPhone's
 * `LAContext.evaluatePolicy(.deviceOwnerAuthentication)`, which on Android is the biometric
 * prompt falling back to the screen lock (`BiometricLock`). True when the owner proved themselves;
 * false for a cancel, a dismissal or an error.
 */
fun interface DeviceOwnerAuthentication {
    suspend fun evaluate(reason: String): Boolean
}

/**
 * Who answers that question for the screens below: the system unless something stands in for it,
 * which is how a picture or a check draws the lock without raising a prompt.
 */
val LocalDeviceOwnerAuthentication = staticCompositionLocalOf<DeviceOwnerAuthentication?> { null }

/** The answer the lock asks for: the stand-in where there is one, otherwise the phone's own prompt. */
@Composable
fun rememberDeviceOwnerAuthentication(): DeviceOwnerAuthentication {
    LocalDeviceOwnerAuthentication.current?.let { return it }
    val activity = LocalActivity.current as? FragmentActivity
    return remember(activity) {
        DeviceOwnerAuthentication { reason ->
            // The prompt is the activity's; without one there is nobody to ask, which is a no.
            activity != null && BiometricLock.authenticate(activity, reason)
        }
    }
}
