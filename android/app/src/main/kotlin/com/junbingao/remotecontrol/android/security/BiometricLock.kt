package com.junbingao.remotecontrol.android.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The app lock's question to the phone's owner: the counterpart of
 * `LAContext.evaluatePolicy(.deviceOwnerAuthentication)` (`docs/DESIGN.md` § "The Android app":
 * Face ID becomes the device's biometric unlock, fingerprint or face, falling back to the screen
 * lock, behind the same switch).
 *
 * Any biometric the phone offers, or its screen lock — the one combination Android accepts from
 * API 29 on, and the one that, like the iPhone's policy, never locks a person out of their own
 * app because a sensor did not recognise them.
 */
object BiometricLock {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Whether the phone has anything to unlock with: a biometric enrolled or a screen lock set. */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Ask, and answer whether the owner proved themselves. A cancel, a dismissal or an error is
     * a no; a sensor that did not match leaves the prompt up for another try, as Face ID does.
     * Leaving the calling coroutine takes the prompt down.
     */
    suspend fun authenticate(activity: FragmentActivity, reason: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(reason)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
            // Cancellation can arrive on any thread; the prompt belongs to the main one.
            continuation.invokeOnCancellation { activity.runOnUiThread { prompt.cancelAuthentication() } }
            prompt.authenticate(info)
        }
}
