package com.junbingao.remotecontrol.win.platform

import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Windows Terminal's keys and right click on the emulator, as far as JediTerm can be asked about them without a window. */
class TerminalKeysTests {
    private fun stroke(key: Int, modifiers: Int): KeyStroke = KeyStroke.getKeyStroke(key, modifiers)

    private val ctrl = InputEvent.CTRL_DOWN_MASK
    private val ctrlShift = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK

    @Test
    fun copyAndPasteAreWindowsTerminals() {
        assertEquals(listOf(stroke(KeyEvent.VK_C, ctrlShift), stroke(KeyEvent.VK_C, ctrl)), TerminalKeys.copy(mac = false))
        assertEquals(listOf(stroke(KeyEvent.VK_V, ctrlShift)), TerminalKeys.paste(mac = false))
        // A Mac keeps its own.
        assertEquals(listOf(stroke(KeyEvent.VK_C, InputEvent.META_DOWN_MASK)), TerminalKeys.copy(mac = true))
        assertEquals(listOf(stroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK)), TerminalKeys.paste(mac = true))
        // The settings JediTerm reads its keys from carry this machine's.
        assertEquals(TerminalKeys.copy(mac = Host.isMac), TerminalTheme.settings.copyActionPresentation.keyStrokes)
        assertEquals(TerminalKeys.paste(mac = Host.isMac), TerminalTheme.settings.pasteActionPresentation.keyStrokes)
    }

    @Test
    fun theShellsOwnKeysReachTheShell() {
        // Ctrl+L, Ctrl+F, Ctrl+Up and Ctrl+Down: JediTerm keeps none of them for itself.
        val settings = TerminalTheme.settings
        for (presentation in listOf(
            settings.clearBufferActionPresentation, settings.findActionPresentation,
            settings.lineUpActionPresentation, settings.lineDownActionPresentation,
        )) {
            assertTrue(presentation.keyStrokes.isEmpty(), presentation.name)
        }
    }

    @Test
    fun aRightClickCopiesWhatIsSelectedAndOtherwisePastes() {
        assertEquals(RightClick.copy, RightClick.action(hasSelection = true))
        assertEquals(RightClick.paste, RightClick.action(hasSelection = false))
    }

    @Test
    fun aRightClickOpensNoMenu() {
        var shown = true
        SwingUtilities.invokeAndWait {
            val style = StyleState()
            val panel = WindowsTerminalPanel(TerminalTheme.settings, TerminalTextBuffer(80, 24, style), style)
            val menu = panel.createPopupMenu(panel)
            menu.show(panel, 0, 0)
            shown = menu.isVisible
        }
        assertFalse(shown)
    }
}
