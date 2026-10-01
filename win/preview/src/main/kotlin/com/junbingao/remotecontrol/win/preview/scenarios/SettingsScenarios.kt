package com.junbingao.remotecontrol.win.preview.scenarios

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.app.signIn
import kotlin.time.Duration.Companion.seconds

/**
 * The settings feature's scenarios: Settings as the admin and as a member in each of its states,
 * the dialogs Settings and Users open, Users and its row menu, and the terminal — at 1280 and
 * below the breakpoints each changes at, in both languages. Settings runs past the window, so its
 * pictures are tall enough to hold every group.
 */
object SettingsScenarios {
    val all: List<PreviewScenario> get() = settings + settingsStates + users + terminal

    private const val tall = 1480

    private val settings: List<PreviewScenario>
        get() = listOf(
            PreviewScenario("settings", route = Route.Settings, height = tall),
            PreviewScenario(
                "settings-member", route = Route.Settings, height = tall, account = PreviewScenario.Account.signedOut,
                settle = 1.seconds, setup = ::signInAsMember,
            ),
            PreviewScenario("settings-900", route = Route.Settings, width = 900, height = tall),
            PreviewScenario("settings-600", route = Route.Settings, width = 600, height = 1600),
            PreviewScenario(
                "settings-member-600", route = Route.Settings, width = 600, height = 1600,
                account = PreviewScenario.Account.signedOut, settle = 1.seconds, setup = ::signInAsMember,
            ),
            PreviewScenario("settings-zh", route = Route.Settings, height = tall, language = InterfaceLanguage.zhHans),
            PreviewScenario("settings-600-zh", route = Route.Settings, width = 600, height = 1600, language = InterfaceLanguage.zhHans),
        )

    /** Every state a row or a dialog of Settings can be in. */
    private val settingsStates: List<PreviewScenario>
        get() = listOf(
            PreviewScenario(
                "settings-change-password", route = Route.Settings, stage = "settings.change-password",
                account = PreviewScenario.Account.signedOut, settle = 1.seconds, setup = ::signInAsMember,
            ),
            PreviewScenario(
                "settings-change-password-error", route = Route.Settings, stage = "settings.change-password-error",
                account = PreviewScenario.Account.signedOut, settle = 2.seconds, setup = ::signInAsMember,
            ),
            PreviewScenario(
                "settings-change-password-600", route = Route.Settings, width = 600, height = 900,
                stage = "settings.change-password", account = PreviewScenario.Account.signedOut, settle = 1.seconds,
                setup = ::signInAsMember,
            ),
            PreviewScenario("settings-sign-out", route = Route.Settings, stage = "settings.sign-out"),
            PreviewScenario("settings-notify-on", route = Route.Settings, setup = { context ->
                context.model.settings.notificationsEnabled = true
            }),
            PreviewScenario("settings-notify-blocked", route = Route.Settings, stage = "settings.notify-blocked"),
            PreviewScenario("settings-polish-on", route = Route.Settings, height = tall, stage = "settings.polish-on", settle = 1.seconds),
            PreviewScenario("settings-polish-menu", route = Route.Settings, height = 1100, stage = "settings.polish-menu", settle = 1.seconds),
            PreviewScenario("settings-polish-unavailable", route = Route.Settings, height = tall, stage = "settings.polish-unavailable"),
            PreviewScenario("settings-no-transcription", route = Route.Settings, height = tall, stage = "settings.no-transcription"),
            PreviewScenario("settings-old-gateway", route = Route.Settings, stage = "settings.old-gateway"),
        )

    private val users: List<PreviewScenario>
        get() = listOf(
            PreviewScenario("users", route = Route.Users),
            PreviewScenario("users-900", route = Route.Users, width = 900),
            PreviewScenario("users-600", route = Route.Users, width = 600),
            PreviewScenario("users-zh", route = Route.Users, language = InterfaceLanguage.zhHans),
            PreviewScenario("users-menu", route = Route.Users, stage = "users.menu"),
            PreviewScenario("users-add", route = Route.Users, stage = "users.add"),
            PreviewScenario("users-add-600", route = Route.Users, width = 600, stage = "users.add"),
            PreviewScenario("users-reset", route = Route.Users, stage = "users.reset"),
            PreviewScenario("users-delete", route = Route.Users, stage = "users.delete"),
            PreviewScenario("users-member", route = Route.Users, account = PreviewScenario.Account.signedOut, settle = 1.seconds, setup = { context ->
                signInAsMember(context)
                context.model.router.replace(Route.Users)
            }),
        )

    private val terminal: List<PreviewScenario>
        get() = listOf(
            PreviewScenario("terminal", settle = 2.seconds, setup = openTerminal(offered = true)),
            PreviewScenario("terminal-ls", stage = "terminal.ls", settle = 2.seconds, setup = openTerminal(offered = true)),
            PreviewScenario("terminal-600", width = 600, settle = 2.seconds, setup = openTerminal(offered = true)),
            PreviewScenario("terminal-480", width = 480, height = 760, settle = 2.seconds, setup = openTerminal(offered = true)),
            PreviewScenario("terminal-exited", stage = "terminal.exit", settle = 2.seconds, setup = openTerminal(offered = true)),
            PreviewScenario("terminal-blocked", settle = 1.seconds, setup = openTerminal(offered = false)),
            PreviewScenario("terminal-zh", language = InterfaceLanguage.zhHans, settle = 2.seconds, setup = openTerminal(offered = true)),
        )

    /** The member the mock gateway ships with and the demo's own, who has no Users row and a Change password row instead. */
    private suspend fun signInAsMember(context: PreviewContext) {
        val origin = context.gateway?.toString() ?: "https://demo.remote-control.invalid"
        context.model.signIn(origin = origin, username = DemoFixtures.memberUsername, password = "devdevdev")
        context.wait(timeout = 10.seconds) { context.model.connection.hasSnapshot }
        context.model.router.replace(Route.Settings)
    }

    /** The terminal of the device that offers one, or of the one that does not. */
    private fun openTerminal(offered: Boolean): suspend (PreviewContext) -> Unit = { context ->
        val device = if (context.gateway == null) {
            if (offered) DemoFixtures.macDeviceID else DemoFixtures.ciDeviceID
        } else {
            if (offered) "dev-mac" else "dev-ci"
        }
        context.model.router.replace(Route.Terminal(deviceId = device))
    }
}
