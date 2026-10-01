package com.junbingao.remotecontrol.android.screens.lock

import androidx.compose.runtime.CompositionLocalProvider
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.harness.IPhoneScreenshotTest
import com.junbingao.remotecontrol.android.harness.Variant
import com.junbingao.remotecontrol.android.harness.picture
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * The lock and the privacy cover in both languages and both appearances. Neither has an iPhone
 * picture: no iPhone UI test locks the app or takes the switcher's snapshot.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class LockPicturesTest(private val variant: Variant) : IPhoneScreenshotTest() {
    @Test
    fun lock() = compose.picture("settings-states", "app-lock", variant) {
        CompositionLocalProvider(LocalDeviceOwnerAuthentication provides DeviceOwnerAuthentication { false }) {
            AppLockView {}
        }
    }

    @Test
    fun privacyCover() = compose.picture("settings-states", "privacy-cover", variant) { AppPrivacyCover() }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = IPhone.variants.map { arrayOf(it) }
    }
}
