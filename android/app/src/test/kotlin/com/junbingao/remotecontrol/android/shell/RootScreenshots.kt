package com.junbingao.remotecontrol.android.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.harness.IPhoneScreenshotTest
import com.junbingao.remotecontrol.android.harness.Variant
import com.junbingao.remotecontrol.android.harness.picture
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.core.state.AppUpdateRequirement
import com.junbingao.remotecontrol.core.state.AppVersion
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import java.net.URI

/**
 * The root's own screens as the iPhone draws them: the sign-in form of a fresh install
 * (`43-launch-without-account`) and the blocking update screen (`ios-update-required`), in both
 * languages and both appearances.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class RootScreenshots(private val variant: Variant) : IPhoneScreenshotTest() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tasks = MainScope()

    @After
    fun stop() {
        tasks.cancel()
    }

    @Test
    fun signIn() {
        val model = AppEnvironment.model(context, LaunchOptions(listOf("--reset-state", "--language=${variant.language}"), debug = true), tasks)
        compose.picture("root", "43-sign-in", variant) { RootView(model) }
    }

    @Test
    fun updateRequired() {
        val requirement = AppUpdateRequirement(
            current = AppVersion("1.9.0"),
            minimum = AppVersion("2.0.0"),
            updateURL = URI("https://testflight.apple.com/join/EXAMPLE"),
        )
        compose.picture("root", "update-required", variant) { UpdateRequiredView(requirement) {} }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = IPhone.variants.map { arrayOf(it) }
    }
}
