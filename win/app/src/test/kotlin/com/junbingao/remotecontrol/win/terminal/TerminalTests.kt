package com.junbingao.remotecontrol.win.terminal

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.TerminalSize
import com.junbingao.remotecontrol.win.app.LaunchOptions
import com.junbingao.remotecontrol.win.app.ModelHarness
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.signOut
import com.junbingao.remotecontrol.win.platform.TerminalFeed
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `web/tests/TerminalPage.test.tsx`, on the demo gateway's shell: the page's rules on top of the core's `TerminalSession`. */
class TerminalTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    private fun demo(block: suspend WinAppModel.(ModelHarness) -> Unit) = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            harness.waitFor { connection.hasSnapshot }
            block(harness)
        }
    }

    /** A screen on the demo's Mac with a stand-in for the emulator. */
    private fun WinAppModel.screen(device: String = DemoFixtures.macDeviceID): Pair<TerminalScreen, TerminalWrites> {
        val screen = TerminalScreen(deviceID = device, model = this)
        val written = TerminalWrites()
        screen.feed.writer = { written.bytes.write(it) }
        return screen to written
    }

    @Test
    fun itWaitsForTheEmulatorsSizeThenDrawsWhatTheShellWrites() = demo { harness ->
        val (screen, written) = screen()
        screen.show()
        assertTrue(screen.status == TerminalScreen.Status.connecting && !screen.started)
        screen.resized(TerminalSize(cols = 100, rows = 30))
        assertTrue(harness.waitFor { screen.status == TerminalScreen.Status.connected })
        assertTrue(harness.waitFor { "demo:~$ " in written.text })
        assertTrue(screen.started)
        screen.type("echo hi\r".toByteArray())
        assertTrue(harness.waitFor { "echo hi" in written.text })
        screen.close()
        signOut()
    }

    @Test
    fun theShellsEndIsSaidWithItsCodeAndANewShellStartsAnother() = demo { harness ->
        val (screen, written) = screen()
        screen.resized(TerminalSize(cols = 80, rows = 24))
        screen.show()
        assertTrue(harness.waitFor { screen.status == TerminalScreen.Status.connected })
        screen.type("exit\r".toByteArray())
        assertTrue(harness.waitFor { screen.status == TerminalScreen.Status.exited })
        assertEquals(0, screen.exitCode)
        val before = written.bytes.size()
        screen.restart()
        assertTrue(harness.waitFor { screen.status == TerminalScreen.Status.connected })
        assertTrue(harness.waitFor { written.bytes.size() > before })
        // The shell that ended stays on screen above the new one's prompt.
        assertFalse(written.contains(TerminalFeed.fullReset))
        screen.close()
        signOut()
    }

    @Test
    fun aDeviceThatOffersNoShellIsNeverAskedForOne() = demo {
        val (screen, _) = screen(device = DemoFixtures.ciDeviceID)
        screen.show()
        screen.resized(TerminalSize(cols = 80, rows = 24))
        delay(300)
        assertFalse(screen.available)
        assertTrue(screen.status == TerminalScreen.Status.connecting && !screen.started)
        assertNull(screen.reason)
        screen.close()
        signOut()
    }

    @Test
    fun aRefusalSaysWhyAndReconnectTriesAgain() = demo { harness ->
        // The device runs four at most; the fifth is refused.
        val open = mutableListOf<TerminalScreen>()
        repeat(4) {
            val (screen, _) = screen()
            screen.show()
            screen.resized(TerminalSize(cols = 80, rows = 24))
            harness.waitFor { screen.status == TerminalScreen.Status.connected }
            open += screen
        }
        val (fifth, _) = screen()
        fifth.show()
        fifth.resized(TerminalSize(cols = 80, rows = 24))
        assertTrue(harness.waitFor { fifth.status == TerminalScreen.Status.disconnected })
        assertEquals(S.errors.conflictTerminal, fifth.reason)
        open.removeAt(0).close()
        delay(200)
        fifth.connect()
        assertTrue(harness.waitFor { fifth.status == TerminalScreen.Status.connected })
        assertNull(fifth.reason)
        fifth.close()
        open.forEach { it.close() }
        signOut()
    }

    @Test
    fun aSignOutEndsTheShellsThePagesHold() = demo { harness ->
        val (screen, _) = screen()
        screen.show()
        screen.resized(TerminalSize(cols = 80, rows = 24))
        assertTrue(harness.waitFor { screen.status == TerminalScreen.Status.connected })
        signOut()
        // Closed: nothing starts it again, whatever the page asks.
        screen.connect()
        delay(200)
        assertFalse(screen.socketOpen)
        assertEquals(TerminalScreen.Status.disconnected, screen.status)
    }
}

/** What the emulator was fed. */
class TerminalWrites {
    val bytes = ByteArrayOutputStream()
    val text: String get() = bytes.toString(Charsets.UTF_8)

    fun contains(sequence: ByteArray): Boolean {
        val all = bytes.toByteArray()
        return (0..all.size - sequence.size).any { start -> sequence.indices.all { all[start + it] == sequence[it] } }
    }
}
