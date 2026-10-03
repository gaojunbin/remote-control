package com.junbingao.remotecontrol.win.unseen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.junbingao.remotecontrol.win.platform.BadgeSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where Windows shows the number (`docs/DESIGN.md` § "A red dot for a session that stopped and waits
 * for you" → Windows): over the taskbar button, and over the notification-area icon while the window
 * is closed; nothing at zero; told again whenever the window opens, which gives it a new button.
 */
class TaskbarBadgeTests {
    private class Surface : BadgeSurface {
        val calls = mutableListOf<String>()
        override fun taskbar(label: String?) {
            calls += "taskbar $label"
        }

        override fun tray(label: String?) {
            calls += "tray $label"
        }
    }

    @Test
    fun theTaskbarCarriesItWhileTheWindowIsOpen() {
        val surface = Surface()
        val badge = SystemTaskbarBadge(surface)
        badge.windowShown(true)
        badge.show(null)
        assertEquals(emptyList(), surface.calls, "nothing at zero, and nothing to take off")
        badge.show("3")
        assertEquals(listOf("taskbar 3"), surface.calls)
        badge.show("3")
        assertEquals(listOf("taskbar 3"), surface.calls, "the same number is not drawn twice")
        badge.show(null)
        assertEquals(listOf("taskbar 3", "taskbar null"), surface.calls)
    }

    @Test
    fun theNotificationAreaCarriesItWhileTheWindowIsClosed() {
        val surface = Surface()
        val badge = SystemTaskbarBadge(surface)
        badge.windowShown(true)
        badge.show("2")
        badge.windowShown(false)
        assertEquals(listOf("taskbar 2", "tray 2"), surface.calls, "a closed window has no button to carry it")
        badge.show("4")
        assertEquals(listOf("taskbar 2", "tray 2", "taskbar 4", "tray 4"), surface.calls)
        badge.windowShown(true)
        assertEquals(listOf("taskbar 2", "tray 2", "taskbar 4", "tray 4", "taskbar 4", "tray null"), surface.calls,
                     "the window back is a new button, told the number, and the notification area gives it up")
        badge.show(null)
        assertEquals("taskbar null", surface.calls.last())
    }

    @Test
    fun theKeeperFollowsTheCountAndHandsItToTheWindowsOwn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val counter = Counter()
            val inert = InertTaskbarBadge()
            val keeper = TaskbarBadgeKeeper(inert) { counter.count }
            keeper.start(scope)
            settle { inert.labels == listOf<String?>(null) }
            assertEquals(listOf<String?>(null), inert.labels, "nothing at zero")
            counter.count = 3
            settle { inert.labels.last() == "3" }
            assertEquals(3, keeper.shown)

            val surface = Surface()
            val system = SystemTaskbarBadge(surface)
            system.windowShown(true)
            keeper.platform = system
            assertEquals(listOf("taskbar 3"), surface.calls, "the window's own is given the number at once")
            counter.count = 0
            settle { surface.calls.last() == "taskbar null" }
            assertEquals("taskbar null", surface.calls.last())
            assertEquals(listOf(null, "3"), inert.labels, "and the stand-in hears no more")
        } finally {
            scope.cancel()
        }
    }

    private class Counter {
        var count by mutableStateOf(0)
    }

    /** Snapshot observers hear of a change when the frame clock says so, which a test says itself. */
    private suspend fun settle(condition: () -> Boolean) {
        repeat(200) {
            Snapshot.sendApplyNotifications()
            if (condition()) return
            delay(10)
        }
    }
}
