package com.junbingao.remotecontrol.android.screens.terminal

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.screens.sessions.ListsDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The iPhone's UI test of the terminal (`ios/UITests/RemoteControlUITests.swift`), on the demo's shell. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class TerminalUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /**
     * Amendment A38, rule 20: tapping a machine that is online and offers a terminal opens a
     * full-screen shell on it — the emulator, the status line, Close, and the key bar a phone needs.
     */
    @Test
    fun tappingADeviceOpensATerminalOnIt() = DemoApp(compose, "testTappingADeviceOpensATerminalOnIt").use { app ->
        val lists = ListsDriver(compose, app)
        app.tap("tab.devices")
        val row = "device.demo-mac-studio"
        app.tap(row)

        app.waitFor("terminal.close")
        assertTrue("the row's own tap opens a shell, titled with the machine's name", lists.hasText("mac-studio-office"))

        app.waitFor("terminal.status")
        app.await("the shell is up", 20_000) { lists.text("terminal.status") == "Connected" }

        // The key bar of rule 20, in the order the design lists it.
        app.waitFor("terminal.key.escape")
        assertTrue("then Tab", lists.left("terminal.key.escape") < lists.left("terminal.key.tab"))
        assertTrue("then the sticky Ctrl", lists.left("terminal.key.tab") < lists.left("terminal.key.control"))
        assertTrue("then the arrows", lists.left("terminal.key.control") < lists.left("terminal.key.up"))

        // The emulator draws its own glyphs and publishes no text to the accessibility tree, so the
        // picture is the evidence, and the status line is the assertion. The demo's shell greets a
        // moment after it opens, as a machine's prompt follows the reply, so the picture waits it out.
        lists.pause(500)
        app.attach("ios-round42-terminal")
        assertEquals("and the line under the title says so once the shell is up", "Connected", lists.text("terminal.status"))

        app.tap("terminal.close")
        app.waitFor(row)
        assertFalse("leaving the screen behind it", app.exists("terminal.close"))
    }
}
