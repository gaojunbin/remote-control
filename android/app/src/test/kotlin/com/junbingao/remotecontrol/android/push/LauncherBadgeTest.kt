package com.junbingao.remotecontrol.android.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.MainActivity
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.strings.L10n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The launcher's badge (A47, `docs/DESIGN.md` § "A red dot for a session that stopped and waits for
 * you" → Android): one quiet notification whose number is the count, in a channel of its own, that
 * opens Sessions — and nothing at all while notifications are not allowed. On Robolectric's
 * notification manager, which posts nothing anywhere.
 */
@RunWith(AndroidJUnit4::class)
class LauncherBadgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @After
    fun english() = L10n.use(L10n.english)

    private fun allowed() = shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Test
    fun theCountRidesOneQuietNotification() {
        allowed()
        assertTrue(LauncherBadge.post(context, 3))
        val badge = posted().single()
        assertEquals(3, badge.number)
        assertEquals(LauncherBadge.channelId, badge.channelId)
        assertEquals("3 sessions are waiting for you", badge.extras.getString(Notification.EXTRA_TITLE))
        val channel = manager.getNotificationChannel(LauncherBadge.channelId)
        assertEquals("no sound, no heads-up", NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertTrue("and still a badge", channel.canShowBadge())
        assertEquals("named in the interface language", "Sessions", channel.name)

        assertTrue(LauncherBadge.post(context, 1))
        val one = posted().single()
        assertEquals("a new count replaces the notification rather than adding one", 1, one.number)
        assertEquals("1 session is waiting for you", one.extras.getString(Notification.EXTRA_TITLE))

        LauncherBadge.remove(context)
        assertTrue("at zero there is none", posted().isEmpty())
    }

    @Test
    fun aTapOpensSessions() {
        allowed()
        LauncherBadge.post(context, 2)
        val tap = shadowOf(posted().single().contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, tap.component?.className)
        assertTrue(LaunchOptions.opensSessions(tap))
        assertEquals("no conversation in particular", null, LaunchOptions.link(tap))
        LauncherBadge.remove(context)
    }

    @Test
    fun nothingIsPostedWhileNotificationsAreNotAllowed() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(LauncherBadge.post(context, 4))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun theSentenceIsTheInterfaceLanguages() {
        assertEquals("1 session is waiting for you", LauncherBadge.sentence(1))
        assertEquals("5 sessions are waiting for you", LauncherBadge.sentence(5))
        L10n.use(L10n.chinese)
        assertEquals("有 1 个会话在等你处理", LauncherBadge.sentence(1))
        assertEquals("有 5 个会话在等你处理", LauncherBadge.sentence(5))
    }
}
