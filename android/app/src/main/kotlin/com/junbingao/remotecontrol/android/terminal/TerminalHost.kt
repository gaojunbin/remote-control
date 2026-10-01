package com.junbingao.remotecontrol.android.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.junbingao.remotecontrol.android.design.Theme
import com.termux.view.TerminalView

/**
 * The shell on a device, drawn by Termux's terminal emulator (amendment A38, `docs/DESIGN.md`
 * § "The terminal": the emulator fills the screen and follows the visible area) — the port of the
 * iPhone's `TerminalHost` around SwiftTerm.
 *
 * @param feed the bytes the device sends, written into the emulator.
 * @param fontSize the type size in points, remembered between terminals.
 * @param onSize the emulator was laid out at this many columns and rows; said once per size.
 * @param onInput bytes the person typed, as the emulator encodes them.
 * @param onFontSize a pinch landed on a new type size.
 * @param scaledFontSize the size a pinch of a scale lands on from a base size — the core's
 *   `TerminalTypeSize.scaled`, which bounds and rounds it.
 */
@Composable
fun TerminalHost(
    feed: TerminalFeed,
    fontSize: Double,
    onSize: (cols: Int, rows: Int) -> Unit,
    onInput: (ByteArray) -> Unit,
    onFontSize: (Double) -> Unit,
    scaledFontSize: (base: Double, scale: Double) -> Double,
    modifier: Modifier = Modifier,
) {
    // The app's own ink on the app's own surface: an emulator's usual light grey on black would
    // read as a different app, and the screen paints the same surface around it.
    val ink = Theme.ink.toArgb()
    val surface = Theme.surface.toArgb()
    val size by rememberUpdatedState(onSize)
    val input by rememberUpdatedState(onInput)
    val pinched by rememberUpdatedState(onFontSize)
    val scaled by rememberUpdatedState(scaledFontSize)
    AndroidView(
        modifier = modifier.testTag("terminal.emulator"),
        factory = { context ->
            val bridge = TerminalBridge(context, feed, fontSize)
            bridge.onSize = { cols, rows -> size(cols, rows) }
            bridge.onInput = { bytes -> input(bytes) }
            bridge.onFontSize = { points -> pinched(points) }
            bridge.scaledFontSize = { base, scale -> scaled(base, scale) }
            bridge.view.also { it.tag = bridge }
        },
        update = { view ->
            val bridge = view.bridge
            bridge.setColors(foreground = ink, background = surface, cursor = ink)
            bridge.setFontSize(fontSize)
        },
        onRelease = { view -> view.bridge.release() },
    )
}

private val TerminalView.bridge: TerminalBridge get() = tag as TerminalBridge
