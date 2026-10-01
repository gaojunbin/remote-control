package com.junbingao.remotecontrol.win.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.state.TerminalSize
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.device
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.platform.TerminalEmulator
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import java.awt.GraphicsEnvironment

/**
 * `/devices/:deviceId/terminal` (A38), `web/src/features/devices/TerminalPage.tsx`, drawn over the
 * whole window: the device's name as the title, a thin status line under it, Close at the trailing
 * edge, and the emulator taking the rest (`docs/DESIGN.md` § "The terminal"). There is no key bar:
 * the keyboard here is real. Nothing that travels through the terminal is stored or logged.
 */
@Composable
fun TerminalPage(deviceId: String) {
    val model = LocalAppModel.current
    key(deviceId) {
        val screen = remember { TerminalScreen(deviceID = deviceId, model = model) }
        TerminalPageBody(screen)
    }
}

/** The page around one `TerminalScreen`, which lives exactly as long as it. */
@Composable
private fun TerminalPageBody(screen: TerminalScreen) {
    val model = LocalAppModel.current
    val layout = LocalLayoutClass.current
    val stage = LocalPreviewStage.current
    val device = model.device(screen.deviceID)
    val say = blocked(screen, model, device)
    val compact = layout.maxWidth480
    VStack(Modifier.fillMaxSize().background(Palette.canvas), spacing = 0.dp) {
        TerminalHead(
            title = device?.name ?: S.terminal.title,
            onBack = { leave(model) },
            onClose = {
                screen.close()
                leave(model)
            },
        ) {
            // A device with no shell to give says so once, where the shell would have been,
            // rather than in the status line too.
            if (say == null) {
                TerminalStatusLine(
                    screen.status, screen.exitCode, screen.reason, screen.missedOutput,
                    onReconnect = { screen.connect() }, onRestart = { screen.restart() },
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Palette.surfaceSunken)) {
            // The emulator is a Swing component, which Compose draws above everything in its box,
            // so the sentence that stands in for a shell takes its place rather than lying over it.
            if (say == null) {
                Emulator(
                    screen,
                    Modifier.fillMaxSize().padding(
                        top = if (compact) Space.sp2 else Space.sp3,
                        start = if (compact) Space.sp3 else Space.sp4,
                        end = if (compact) Space.sp3 else Space.sp4,
                        bottom = if (compact) Space.sp3 else Space.sp4,
                    ),
                )
            } else {
                TerminalBlocked(say)
            }
        }
    }
    DisposableEffect(screen) {
        screen.show()
        onDispose { screen.close() }
    }
    LaunchedEffect(screen) {
        snapshotFlow { screen.available && screen.socketOpen }.drop(1).collect { up -> if (up) screen.connect() }
    }
    LaunchedEffect(screen) {
        // A render types one line into the shell once it is connected: a command, or the `exit` that ends it.
        val line = stagedLines[stage] ?: return@LaunchedEffect
        snapshotFlow { screen.status }.first { it == TerminalScreen.Status.connected }
        delay(300)
        screen.type(line.toByteArray())
    }
}

private val stagedLines = mapOf("terminal.ls" to "ls\r", "terminal.exit" to "exit\r")

/**
 * The emulator, fed by the screen. A render has no window to put a Swing component in, so there
 * the stand-in measures the same grid and draws what the shell wrote (`TerminalStandIn`).
 */
@Composable
private fun Emulator(screen: TerminalScreen, modifier: Modifier) {
    val onSize = { cols: Int, rows: Int -> screen.resized(TerminalSize(cols = cols, rows = rows)) }
    if (GraphicsEnvironment.isHeadless()) {
        TerminalStandIn(screen.feed, onSize, modifier)
    } else {
        TerminalEmulator(screen.feed, onSize = onSize, onInput = { screen.type(it) }, modifier = modifier)
    }
}

/**
 * Rule 20 for a link somebody kept, and for a device that goes offline while the page is open:
 * what the row would have said instead of opening. Nothing is said before the device list has
 * arrived, and nothing once a shell has run here — a loss then is the status line's to tell.
 */
private fun blocked(screen: TerminalScreen, model: WinAppModel, device: Device?): String? {
    if (screen.started || !(model.connection.hasSnapshot || device != null)) return null
    if (device == null) return S.terminal.gone
    if (!device.online) return S.devices.deviceOffline
    return if (device.offersTerminal) null else S.devices.noTerminal
}

private fun leave(model: WinAppModel) {
    model.router.go(Route.Devices)
}

/** `.terminal-blocked`: a device that cannot give a shell says so where the shell would have been. */
@Composable
private fun TerminalBlocked(text: String) {
    Box(Modifier.fillMaxSize().background(Palette.surfaceSunken).padding(Space.sp4)) {
        Text(text, css(FontSize.fs14), Modifier.fillMaxSize(), color = Palette.inkSecondary, textAlign = TextAlign.Center)
    }
}
