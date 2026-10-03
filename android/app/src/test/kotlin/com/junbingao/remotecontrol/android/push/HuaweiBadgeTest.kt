package com.junbingao.remotecontrol.android.push

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.MainActivity
import com.junbingao.remotecontrol.android.screens.alerts.SystemBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The icon's number on Huawei's and Honor's launchers (A47): their badge interface is told exactly
 * what the badge's notification shows — the count where it was posted, 0 everywhere else — and a
 * launcher without the interface costs nothing. On Robolectric, with stand-ins for the two
 * launchers' providers.
 */
@RunWith(AndroidJUnit4::class)
class HuaweiBadgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** A launcher's settings provider that records every call made to it. */
    class LauncherProvider : ContentProvider() {
        val calls = mutableListOf<Pair<String, Bundle?>>()

        override fun onCreate(): Boolean = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            calls += method to extras
            return null
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                           sortOrder: String?): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun launchers(): List<LauncherProvider> = listOf(
        Robolectric.setupContentProvider(LauncherProvider::class.java, "com.huawei.android.launcher.settings"),
        Robolectric.setupContentProvider(LauncherProvider::class.java, "com.hihonor.android.launcher.settings"),
    )

    private fun allowed(granted: Boolean) {
        val application = shadowOf(context as Application)
        if (granted) application.grantPermissions(Manifest.permission.POST_NOTIFICATIONS) else application.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** The number each launcher was last told, after checking the call names the app as the launcher's interface wants. */
    private fun lastNumber(launcher: LauncherProvider): Int {
        val (method, extras) = launcher.calls.last()
        assertEquals("change_badge", method)
        assertEquals(context.packageName, extras?.getString("package"))
        assertEquals("the activity the launcher opens", MainActivity::class.java.name, extras?.getString("class"))
        return extras?.getInt("badgenumber") ?: -1
    }

    @Test
    fun theCountIsHandedOnAsTheNotificationShowsIt() {
        allowed(true)
        val launchers = launchers()
        SystemBadge(context).setBadge(3)
        assertEquals(3, shadowOf(manager).allNotifications.single().number)
        for (launcher in launchers) assertEquals("Huawei's and Honor's are told the notification's number", 3, lastNumber(launcher))

        SystemBadge(context).setBadge(0)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        for (launcher in launchers) assertEquals("and nothing at zero", 0, lastNumber(launcher))
    }

    @Test
    fun aNumberNoNotificationShowsIsZero() {
        allowed(false)
        val launchers = launchers()
        SystemBadge(context).setBadge(4)
        assertTrue("nothing is posted while notifications are not allowed", shadowOf(manager).allNotifications.isEmpty())
        for (launcher in launchers) assertEquals("so the launchers are told none either", 0, lastNumber(launcher))
    }

    @Test
    fun aLauncherWithoutTheInterfaceCostsNothing() {
        allowed(true)
        SystemBadge(context).setBadge(2)
        assertEquals("the notification is the badge there", 2, shadowOf(manager).allNotifications.single().number)
        SystemBadge(context).setBadge(0)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
