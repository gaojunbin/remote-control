package com.junbingao.remotecontrol.win.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.jediterm.terminal.ui.JediTermWidget
import java.awt.Dimension
import javax.swing.JScrollBar

/**
 * JediTerm's view, filling the page's body (`docs/DESIGN.md` § "The terminal"): it renders,
 * selects, scrolls and turns keys into the bytes a terminal expects; copy and paste are its own.
 * Everything else — what to do with the bytes, what the status line says — belongs to the page.
 *
 * `feed` carries the device's bytes in; `onSize` hears the grid the emulator was laid out at, once
 * per size, and `onInput` the bytes the person typed or pasted, both on the window's thread. The
 * emulator is a Swing component in the window, so a render (which has no window) draws none.
 */
@Composable
fun TerminalEmulator(
    feed: TerminalFeed,
    onSize: (cols: Int, rows: Int) -> Unit,
    onInput: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = rememberUpdatedState(onSize)
    val input = rememberUpdatedState(onInput)
    val connector = remember { FeedConnector() }
    val widget = remember { QuietTerminalWidget(connector) }
    DisposableEffect(feed, connector) {
        var reported: Pair<Int, Int>? = null
        connector.onSize = { cols, rows ->
            // One report per size: a collapsed view fits to nothing, and nothing is not a size.
            if (reported != cols to rows) {
                reported = cols to rows
                javax.swing.SwingUtilities.invokeLater { size.value(cols, rows) }
            }
        }
        connector.onInput = { bytes -> javax.swing.SwingUtilities.invokeLater { input.value(bytes) } }
        feed.writer = connector::feed
        onDispose {
            feed.writer = null
            widget.close()
        }
    }
    SwingPanel(factory = { widget }, modifier = modifier)
}

/**
 * The web's emulator draws no bar at rest; JediTerm's is a Swing bar whose track is always drawn.
 * The wheel scrolls the scrollback without it.
 */
private class QuietTerminalWidget(connector: FeedConnector) :
    JediTermWidget(80, 24, TerminalTheme.settings) {
    init {
        setTtyConnector(connector)
        start()
    }

    override fun createScrollBar(): JScrollBar = object : JScrollBar() {
        override fun getPreferredSize(): Dimension = Dimension(0, 0)
    }
}
