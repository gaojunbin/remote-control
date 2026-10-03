package com.junbingao.remotecontrol.android.push

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.MainActivity
import com.junbingao.remotecontrol.android.strings.L10n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The channels, their words, and one notification from the post to the tap. */
@RunWith(AndroidJUnit4::class)
class NotificationChannelsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @After
    fun english() = L10n.use(L10n.english)

    @Test
    fun oneChannelPerKindTheGatewayPushesFor() {
        assertEquals(
            listOf("turn_completed", "needs_approval", "needs_input", "error", "limit_reached", "resumed", "resume_dropped"),
            NotificationChannels.kinds.map { it.rawValue },
        )
        assertEquals("rc.needs_input", NotificationChannels.channelId("needs_input"))
    }

    @Test
    fun eachKindIsAnnouncedInTheInterfaceLanguage() {
        assertEquals("Turn finished", NotificationChannels.word("turn_completed"))
        L10n.use(L10n.chinese)
        assertEquals("本轮已完成", NotificationChannels.word("turn_completed"))
        assertEquals("some_future_kind", NotificationChannels.word("some_future_kind"))
    }

    @Test
    fun channelsAreRenamedInPlaceWhenTheLanguageChanges() {
        NotificationChannels.ensure(context)
        assertEquals("Needs your approval", manager.getNotificationChannel("rc.needs_approval").name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("rc.needs_approval").importance)
        L10n.use(L10n.chinese)
        NotificationChannels.ensure(context)
        assertEquals("需要你批准", manager.getNotificationChannel("rc.needs_approval").name)
        assertEquals(7, manager.notificationChannels.count { it.id.startsWith("rc.") })
    }

    /**
     * A launcher that shows numbers adds up every notification it may badge, so only the badge's
     * own channel counts on the icon (A47): a "Turn finished" beside it must not make one session two.
     */
    @Test
    fun theNewsNeverCountsOnTheIcon() {
        NotificationChannels.ensure(context)
        for (kind in NotificationChannels.kinds) {
            assertFalse(kind.rawValue, manager.getNotificationChannel(NotificationChannels.channelId(kind.rawValue)).canShowBadge())
        }
        LauncherBadge.ensureChannel(context)
        assertTrue("the badge's own channel is the one that does", manager.getNotificationChannel(LauncherBadge.channelId).canShowBadge())
    }

    @Test
    fun nothingIsPostedWhileNotificationsAreNotAllowed() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(post())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun aTapOpensTheSessionThroughTheAppsOwnLink() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(PushAuthorization.authorized, NotificationAuthorization.status(context))
        assertTrue(post())
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("rc.turn_completed", posted.channelId)
        assertEquals("mac-studio-office", posted.extras.getString("android.title"))
        assertEquals("Turn finished", posted.extras.getString("android.text"))
        assertEquals("mac-studio-office/s1", posted.group)
        val tap = shadowOf(posted.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, tap.action)
        assertEquals("remotecontrol://session?device=mac-studio-office&id=s1", tap.dataString)
        assertEquals(MainActivity::class.java.name, tap.component?.className)
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun post() = LocalNotifications.post(
        context, kind = "turn_completed", identifier = "turn/mac-studio-office/s1/turn_completed/1",
        title = "mac-studio-office", body = NotificationChannels.word("turn_completed"),
        thread = "mac-studio-office/s1", deepLink = "remotecontrol://session?device=mac-studio-office&id=s1",
    )
}
