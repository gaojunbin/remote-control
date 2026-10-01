package com.junbingao.remotecontrol.win.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import com.junbingao.remotecontrol.win.standin.InterfaceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The breakpoints, the launch arguments and the keyboard map: the window's shell without a window. */
class ShellTests {
    @Test
    fun theBreakpointsHoldAtTheirOwnWidth() {
        assertTrue(LayoutClass(760f, 800f).maxWidth760)
        assertFalse(LayoutClass(761f, 800f).maxWidth760)
        assertTrue(LayoutClass(1023f, 800f).maxWidth1023)
        assertFalse(LayoutClass(1024f, 800f).maxWidth1023)
        assertTrue(LayoutClass(640f, 800f).maxWidth640 && LayoutClass(480f, 800f).maxWidth480 && LayoutClass(420f, 800f).maxWidth420)
        assertFalse(LayoutClass(421f, 800f).maxWidth420)
    }

    @Test
    fun launchArgumentsAreTheMacAppsOwn() {
        val options = LaunchOptions(listOf("--demo", "--ephemeral", "--language=zh-Hans", "--registration-open"))
        assertTrue(options.demo && options.ephemeral && options.registrationOpen)
        assertFalse(options.demoAccount || options.demoUpdateRequired || options.resetState)
        assertEquals(InterfaceLanguage.zhHans, options.language)
        assertNull(LaunchOptions(listOf("--language=fr")).language)
        assertEquals(LaunchOptions(), LaunchOptions(emptyList()))
        assertTrue(LaunchOptions(listOf("--demo-account", "--demo-update-required", "--reset-state")).let { it.demoAccount && it.demoUpdateRequired && it.resetState })
    }

    @Test
    fun ctrlTakesCommandsPlaceAndAltWalksTheHistory() {
        fun ctrl(key: Key) = AppCommands.commandFor(key, ctrl = true, alt = false, shift = false, meta = false)
        fun alt(key: Key) = AppCommands.commandFor(key, ctrl = false, alt = true, shift = false, meta = false)
        assertEquals(AppCommand.devices, ctrl(Key.One))
        assertEquals(AppCommand.sessions, ctrl(Key.Two))
        assertEquals(AppCommand.settingsTab, ctrl(Key.Three))
        assertEquals(AppCommand.settings, ctrl(Key.Comma))
        assertEquals(AppCommand.newSession, ctrl(Key.N))
        assertEquals(AppCommand.back, alt(Key.DirectionLeft))
        assertEquals(AppCommand.forward, alt(Key.DirectionRight))
        // Nothing else: a plain key, a shifted one and the Windows key are the page's.
        assertNull(AppCommands.commandFor(Key.N, ctrl = false, alt = false, shift = false, meta = false))
        assertNull(AppCommands.commandFor(Key.N, ctrl = true, alt = false, shift = true, meta = false))
        assertNull(AppCommands.commandFor(Key.DirectionLeft, ctrl = true, alt = false, shift = false, meta = false))
        assertNull(AppCommands.commandFor(Key.One, ctrl = true, alt = true, shift = false, meta = false))
    }

    @Test
    fun theCommandsMoveTheRouterOnlyWhenSomeoneIsSignedIn() {
        val router = Router()
        var signedIn = false
        val commands = AppCommands(router) { signedIn }
        commands.perform(AppCommand.devices)
        assertEquals(Route.Landing, router.route)
        signedIn = true
        commands.perform(AppCommand.devices)
        commands.perform(AppCommand.settings)
        assertEquals(Route.Settings, router.route)
        assertTrue(commands.handle(PointerButton.Back))
        assertEquals(Route.Devices, router.route)
        assertTrue(commands.handle(PointerButton.Forward))
        assertEquals(Route.Settings, router.route)
        assertFalse(commands.handle(PointerButton.Primary))
        commands.perform(AppCommand.newSession)
        assertEquals(Route.Sessions, router.route)
        assertTrue(router.takeNewSessionRequest())
    }
}
