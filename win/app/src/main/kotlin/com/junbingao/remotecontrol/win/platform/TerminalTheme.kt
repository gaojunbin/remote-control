package com.junbingao.remotecontrol.win.platform

import com.jediterm.core.Color
import com.jediterm.terminal.HyperlinkStyle
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.ui.TerminalActionPresentation
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import java.awt.Font
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * `terminalTheme.ts` — A38: how the emulator is dressed, as the Mac dresses its own.
 *
 * The app is one light canvas (`docs/DESIGN.md`), so the terminal is light too: a dark rectangle
 * dropped into these screens would read as a different application. The sixteen ANSI colours are
 * chosen for ink on paper — the same greens, ambers and reds the rest of the app uses, darkened
 * enough to stay legible on a near-white background. The face is `ui-monospace` at 13 px as the
 * web's stack resolves it: Consolas on Windows, SF Mono on a Mac. Rows are the font's own height,
 * as the Mac's emulator draws them.
 */
object TerminalTheme {
    const val fontSize = 13f

    /** §7.3 keeps 64 KiB on the device; the emulator holds its own lines. */
    const val scrollback = 5_000

    const val background = 0xFAFAF9
    const val foreground = 0x111111
    const val cursor = 0x111111
    const val cursorAccent = 0xFAFAF9

    /** Selected text: 14 % ink over the background, as the web's selection draws it. */
    const val selection = 0xDADAD9

    /** Black, red, green, yellow, blue, magenta, cyan, white, then the bright eight. */
    val ansi = intArrayOf(
        0x111111, 0xC23A2C, 0x1F7A4D, 0x8A6100, 0x2A56B0, 0x8A4FA0, 0x1F6F79, 0x6B6B6B,
        0x767570, 0xD23F31, 0x22A06B, 0xB07C00, 0x1F4FA0, 0x7A3F92, 0x12656F, 0x111111,
    )

    val font: Font by lazy {
        val family = if (Host.isMac) Font.MONOSPACED else "Consolas"
        Font(family, Font.PLAIN, 13).deriveFont(fontSize)
    }

    fun color(hex: Int): Color = Color((hex shr 16) and 0xFF, (hex shr 8) and 0xFF, hex and 0xFF)

    private val palette = object : ColorPalette() {
        override fun getForegroundByColorIndex(index: Int): Color = color(ansi[index.coerceIn(0, 15)])

        override fun getBackgroundByColorIndex(index: Int): Color = color(ansi[index.coerceIn(0, 15)])
    }

    /**
     * JediTerm's settings for the page. xterm.js, as the web opens it, rings no bell, opens no link a
     * shell names and copies nothing a shell asks it to; the person's own copy is the one way out.
     * Its keys are Windows Terminal's (`TerminalKeys`), and the keys JediTerm keeps for clearing its
     * buffer, finding and scrolling by a line — Ctrl+L, Ctrl+F, Ctrl+Up and Ctrl+Down on Windows —
     * are the shell's, as they are on the web, which has none of the three.
     */
    val settings = object : DefaultSettingsProvider() {
        override fun getTerminalColorPalette(): ColorPalette = palette

        override fun getTerminalFont(): Font = font

        override fun getTerminalFontSize(): Float = fontSize

        override fun getLineSpacing(): Float = 1f

        override fun getDefaultForeground(): TerminalColor = TerminalColor(color(foreground))

        override fun getDefaultBackground(): TerminalColor = TerminalColor(color(background))

        override fun getSelectionColor(): TextStyle = TextStyle(TerminalColor(color(foreground)), TerminalColor(color(selection)))

        override fun getHyperlinkHighlightingMode(): HyperlinkStyle.HighlightMode = HyperlinkStyle.HighlightMode.NEVER

        override fun useInverseSelectionColor(): Boolean = false

        override fun audibleBell(): Boolean = false

        override fun getBufferMaxLinesCount(): Int = scrollback

        override fun caretBlinkingMs(): Int = 600

        override fun getCopyActionPresentation(): TerminalActionPresentation =
            TerminalActionPresentation("Copy", TerminalKeys.copy(mac = Host.isMac))

        override fun getPasteActionPresentation(): TerminalActionPresentation =
            TerminalActionPresentation("Paste", TerminalKeys.paste(mac = Host.isMac))

        override fun getClearBufferActionPresentation(): TerminalActionPresentation = TerminalActionPresentation("Clear Buffer", emptyList())

        override fun getFindActionPresentation(): TerminalActionPresentation = TerminalActionPresentation("Find", emptyList())

        override fun getLineUpActionPresentation(): TerminalActionPresentation = TerminalActionPresentation("Line Up", emptyList())

        override fun getLineDownActionPresentation(): TerminalActionPresentation = TerminalActionPresentation("Line Down", emptyList())
    }
}

/**
 * The emulator's copy and paste, as Windows Terminal has them: Ctrl+Shift+C, and Ctrl+C while text
 * is selected — JediTerm sends a Ctrl+C with nothing selected on to the shell, and takes the
 * selection away after the copy a Ctrl+C makes — and Ctrl+Shift+V. On a Mac, ⌘C and ⌘V.
 */
object TerminalKeys {
    fun copy(mac: Boolean): List<KeyStroke> =
        if (mac) listOf(stroke(KeyEvent.VK_C, InputEvent.META_DOWN_MASK))
        else listOf(stroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK), stroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK))

    fun paste(mac: Boolean): List<KeyStroke> =
        if (mac) listOf(stroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK))
        else listOf(stroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK))

    private fun stroke(key: Int, modifiers: Int): KeyStroke = KeyStroke.getKeyStroke(key, modifiers)
}
