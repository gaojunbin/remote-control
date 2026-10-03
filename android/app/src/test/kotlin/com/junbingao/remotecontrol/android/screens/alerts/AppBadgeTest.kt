package com.junbingao.remotecontrol.android.screens.alerts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Amendment A47's badge keeper on a platform with no notification manager behind it: it follows the
 * count, sets each number once, and puts it back when told to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppBadgeTest {
    private class Count {
        var value by mutableStateOf(0)
    }

    @Test
    fun theBadgeFollowsTheCountAndSetsEachNumberOnce() = runTest {
        val platform = FakeBadgePlatform()
        val count = Count()
        val badge = AppBadge(platform, backgroundScope) { count.value }
        assertNull("nothing is put on the icon before the keeper starts", badge.shown)
        badge.start()
        runCurrent()
        assertEquals(listOf(0), platform.counts)

        count.value = 2
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(listOf(0, 2), platform.counts)
        badge.start()
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals("a second start follows nothing twice", listOf(0, 2), platform.counts)

        badge.reassert()
        assertEquals("told to, it puts the number back whatever it last put there", listOf(0, 2, 2), platform.counts)
        count.value = 0
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(listOf(0, 2, 2, 0), platform.counts)
        assertEquals(0, badge.shown)
    }
}

/** The icon's badge, with no notification manager behind it. */
internal class FakeBadgePlatform : BadgePlatform {
    val counts = mutableListOf<Int>()

    override fun setBadge(count: Int) {
        counts += count
    }
}
