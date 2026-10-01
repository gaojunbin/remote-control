package com.junbingao.remotecontrol.win.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which conversation a click in the notification area opens. Nothing is posted: the rule is the
 * part of Windows' notifications this Mac can check.
 */
class ToastTests {
    private var clock = 10_000L
    private val clicks = ToastClicks { clock }
    private val target = ToastTarget("dev-mac", "ses-vite")

    @Test
    fun aClickOnTheNotificationOpensItsConversationOnce() {
        clicks.posted(target)
        clock += 3_000
        assertEquals(target, clicks.action())
        assertNull(clicks.action())
    }

    @Test
    fun aDoubleClickOnTheIconOpensTheWindowAndKeepsTheNotification() {
        clicks.posted(target)
        clock += 5_000
        clicks.iconPressed()
        clock += 200
        assertNull(clicks.action())
        clock += 5_000
        assertEquals(target, clicks.action())
    }

    @Test
    fun theNewestNotificationIsTheOneAClickOpens() {
        clicks.posted(target)
        val newer = ToastTarget("dev-linux", "ses-api")
        clicks.posted(newer)
        assertEquals(newer, clicks.action())
    }

    @Test
    fun signingOutLeavesNothingToOpen() {
        clicks.posted(target)
        clicks.cleared()
        assertNull(clicks.action())
    }

    @Test
    fun anywhereButWindowsNotificationsStayInTheProcess() {
        val toasts = InertToasts()
        toasts.post(ToastNotice("Remote Control", "mac-studio: turn finished", target))
        assertEquals(1, toasts.posted.size)
        toasts.removeDelivered()
        assertTrue(toasts.posted.isEmpty())
        if (!Host.isWindows) assertTrue(TrayToasts.make(null) is InertToasts)
    }
}
