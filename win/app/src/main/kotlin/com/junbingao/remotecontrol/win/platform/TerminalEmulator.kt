package com.junbingao.remotecontrol.win.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalActionPresentation
import com.jediterm.terminal.ui.TerminalActionProvider
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.SettingsProvider
import java.awt.Component
import java.awt.Dimension
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPopupMenu
import javax.swing.JScrollBar

/**
 * JediTerm's view, filling the page's body (`docs/DESIGN.md` § "The terminal"): it renders,
 * selects, scrolls and turns keys into the bytes a terminal expects; copy and paste are its own, on
 * Windows Terminal's keys (`TerminalKeys`) and its right click (`RightClick`). Everything else —
 * what to do with the bytes, what the status line says — belongs to the page.
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

    override fun createTerminalPanel(settings: SettingsProvider, styleState: StyleState, textBuffer: TerminalTextBuffer): TerminalPanel =
        WindowsTerminalPanel(settings, textBuffer, styleState)
}

/**
 * Windows Terminal's right click: what is selected is copied, and with nothing selected the
 * clipboard is pasted.
 */
enum class RightClick {
    copy,
    paste,
    ;

    companion object {
        fun action(hasSelection: Boolean): RightClick = if (hasSelection) copy else paste
    }
}

/**
 * The emulator's panel with Windows Terminal's right click and no menu: JediTerm's is a Swing
 * popup, and the window draws none of its own. A program that asked for the mouse gets the click,
 * as it does from Windows Terminal, unless Shift is held.
 */
internal class WindowsTerminalPanel(
    private val settings: SettingsProvider,
    textBuffer: TerminalTextBuffer,
    styleState: StyleState,
) : TerminalPanel(settings, textBuffer, styleState) {
    override fun processMouseEvent(event: MouseEvent) {
        if (event.id == MouseEvent.MOUSE_PRESSED && event.button == MouseEvent.BUTTON3 && isLocalMouseAction(event)) {
            when (RightClick.action(hasSelection = selection != null)) {
                // The copy a Ctrl+C makes, which takes the selection away after it.
                RightClick.copy -> action(settings.copyActionPresentation)
                    ?.actionPerformed(KeyEvent(this, KeyEvent.KEY_PRESSED, event.`when`, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_C, 'c'))
                RightClick.paste -> action(settings.pasteActionPresentation)?.actionPerformed(null)
            }
        }
        super.processMouseEvent(event)
    }

    public override fun createPopupMenu(actionProvider: TerminalActionProvider): JPopupMenu = object : JPopupMenu() {
        override fun show(invoker: Component?, x: Int, y: Int) {}
    }

    private fun action(presentation: TerminalActionPresentation) = actions.firstOrNull { it.name == presentation.name }
}
