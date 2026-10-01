package com.junbingao.remotecontrol.android.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.junbingao.remotecontrol.android.design.Appearance
import com.junbingao.remotecontrol.android.design.ProvideAppearance
import com.junbingao.remotecontrol.android.design.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The composable the terminal screen places: laid out, fed, and drawn at the iPhone's size. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w402dp-h874dp-xxhdpi")
class TerminalHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theHostReportsItsGridAndDrawsTheShellInTheAppsInk() {
        val feed = TerminalFeed()
        val sizes = mutableListOf<TerminalGrid>()
        compose.setContent {
            ProvideAppearance(Appearance(isDark = false)) {
                Box(Modifier.fillMaxSize().background(Theme.surface)) {
                    TerminalHost(
                        feed = feed,
                        fontSize = 12.0,
                        onSize = { cols, rows -> sizes += TerminalGrid(cols, rows) },
                        onInput = {},
                        onFontSize = {},
                        scaledFontSize = { base, _ -> base },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.runOnIdle {
            feed.write(
                ("Remote Control demo shell — nothing here reaches a real machine.\r\n" +
                    "demo:~$ ls\r\n" +
                    "\u001b[1;34mgateway\u001b[0m  \u001b[1;34mios\u001b[0m  \u001b[1;34mweb\u001b[0m  README.md\r\n" +
                    "demo:~$ ").toByteArray(),
            )
        }
        compose.onNodeWithTag("terminal.emulator").assertExists()
        compose.runOnIdle {
            assertEquals("one layout, one report", 1, sizes.size)
            assertNotNull(feed.size)
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/terminal/terminal-host.png")
    }
}
