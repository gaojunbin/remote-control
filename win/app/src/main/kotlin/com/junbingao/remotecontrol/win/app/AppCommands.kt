package com.junbingao.remotecontrol.win.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton

/**
 * The keyboard map (`docs/DESIGN.md` § "The Windows app" → **Navigation is the web's history with
 * Windows' keys**). There is no menu bar: every command the Mac's menus hold is a shortcut here —
 * Ctrl takes ⌘'s place: Ctrl+1, Ctrl+2 and Ctrl+3 for the three tabs, Ctrl+, for Settings, Ctrl+N
 * for New session — and back and forward are Alt+Left and Alt+Right and the mouse's back and
 * forward buttons, as in a Windows browser.
 */
enum class AppCommand { newSession, settings, devices, sessions, settingsTab, back, forward }

class AppCommands(
    private val router: Router,
    /** Nothing but the form, or the Update required screen, is reachable without an account the gateway accepts. */
    private val canNavigate: () -> Boolean,
) {
    /** Performs what a key press asks for; true when it was one of the map's. */
    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val command = commandFor(event.key, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed)
            ?: return false
        perform(command)
        return true
    }

    /** The mouse's back and forward buttons. */
    fun handle(button: PointerButton?): Boolean {
        val command = when (button) {
            PointerButton.Back -> AppCommand.back
            PointerButton.Forward -> AppCommand.forward
            else -> return false
        }
        perform(command)
        return true
    }

    fun perform(command: AppCommand) {
        if (!canNavigate()) return
        when (command) {
            AppCommand.newSession -> router.requestNewSession()
            AppCommand.settings, AppCommand.settingsTab -> router.go(Route.Settings)
            AppCommand.devices -> router.go(Route.Devices)
            AppCommand.sessions -> router.go(Route.Sessions)
            AppCommand.back -> router.back()
            AppCommand.forward -> router.forward()
        }
    }

    companion object {
        /** The map itself, with nothing around it. */
        fun commandFor(key: Key, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean): AppCommand? {
            if (shift || meta) return null
            if (ctrl && !alt) {
                return when (key) {
                    Key.N -> AppCommand.newSession
                    Key.Comma -> AppCommand.settings
                    Key.One, Key.NumPad1 -> AppCommand.devices
                    Key.Two, Key.NumPad2 -> AppCommand.sessions
                    Key.Three, Key.NumPad3 -> AppCommand.settingsTab
                    else -> null
                }
            }
            if (alt && !ctrl) {
                return when (key) {
                    Key.DirectionLeft -> AppCommand.back
                    Key.DirectionRight -> AppCommand.forward
                    else -> null
                }
            }
            return null
        }
    }
}
