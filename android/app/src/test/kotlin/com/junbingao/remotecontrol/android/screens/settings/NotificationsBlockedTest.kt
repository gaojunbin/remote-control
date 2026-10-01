package com.junbingao.remotecontrol.android.screens.settings

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.push.PushAuthorization
import com.junbingao.remotecontrol.android.screens.shell.Driving
import com.junbingao.remotecontrol.android.screens.shell.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `docs/DESIGN.md` § "The Settings screen": the one row whose tap is not its control's. A phone
 * that was asked once and said no can only be turned back on in Android Settings, so the row says
 * so in place of its sentence and the whole of it — the inert switch included — goes there. The
 * system's settings screen is only asked for; nothing on this machine opens it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class NotificationsBlockedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun aPhoneThatSaidNo() {
        Phone.hasAScreenLock()
        // What `NotificationAuthorization` reads as asked and refused on Android 13 and later.
        context.getSharedPreferences("notifications", Context.MODE_PRIVATE).edit(commit = true) { putBoolean("asked", true) }
    }

    @Test
    fun theBlockedRowGoesToAndroidSettings() = DemoApp(compose, "notifications-blocked").use { app ->
        val drive = Driving(compose, app)
        drive.openSettingsTab()
        assertTrue("the row says the system blocked it", drive.scrollDown(toTag = "settings.notifications.blocked"))
        assertEquals(PushAuthorization.denied, app.model.push.authorization)
        assertTrue("in Android's words", drive.label("settings.notifications.blocked").contains("Blocked in Android Settings. Tap to open them."))
        assertFalse("and there is no live switch beside it", drive.exists("settings.notifications"))

        // A tap where the switch is drawn is the row's, not the switch's.
        app.node("settings.notifications.blocked").performTouchInput { click(Offset(width - 48f * density, height / 2f)) }
        compose.waitForIdle()
        val opened = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        assertEquals("the app's notification settings are asked for", Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened?.action)
        assertEquals(context.packageName, opened?.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
