package com.junbingao.remotecontrol.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One tab's stack: pushes, Back, and a path assigned whole as the iPhone assigns its path. */
class NavigatorTest {
    @Test
    fun aPushStacksAScreenAndBackLeavesIt() {
        val stack = Navigator("devices")
        assertFalse("the root has no back button", stack.canPop)
        stack.push("mac-studio-office")
        stack.push("terminal")
        assertEquals(listOf("devices", "mac-studio-office", "terminal"), stack.routes)
        assertTrue(stack.pop())
        assertEquals("mac-studio-office", stack.top.route)
        assertTrue(stack.pop())
        assertFalse("Back at the root belongs to the system", stack.pop())
        assertEquals(listOf("devices"), stack.routes)
    }

    @Test
    fun aNewScreenIsANewEntryEvenForTheSameRoute() {
        val stack = Navigator("sessions")
        stack.push("a")
        val first = stack.top.id
        stack.pop()
        stack.push("a")
        assertTrue("its saved state starts fresh", stack.top.id > first)
    }

    @Test
    fun assigningAPathReplacesWhatWasStackedOverTheRoot() {
        val stack = Navigator("sessions")
        stack.push("held")
        stack.setPath(listOf("opened"))
        assertEquals(listOf("sessions", "opened"), stack.routes)
        stack.push("more")
        stack.popToRoot()
        assertEquals(listOf("sessions"), stack.routes)
    }
}
