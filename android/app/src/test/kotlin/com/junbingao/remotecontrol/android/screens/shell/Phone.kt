package com.junbingao.remotecontrol.android.screens.shell

import android.content.Context
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.test.core.app.ApplicationProvider
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager

/** The phone a test runs on, set up as the iPhone the reference pictures were taken on. */
object Phone {
    /**
     * Something to unlock with — a biometric, or the screen lock it falls back to — as the
     * simulator has a passcode, so Settings offers the app lock as the iPhone does. Only the
     * system's answer to "can this phone authenticate" changes; nothing is ever asked.
     */
    fun hasAScreenLock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
        val shadow = Shadow.extract<ShadowBiometricManager>(manager)
        shadow.setCanAuthenticate(true)
        shadow.setAuthenticatorType(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
    }
}
