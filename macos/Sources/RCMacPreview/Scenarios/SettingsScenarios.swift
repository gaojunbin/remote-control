import RCCore
import RCMac
import SwiftUI

/// The settings feature's scenarios: Settings as the admin and as a member in
/// each of its states, the dialogs Settings and Users open, Users and its row
/// menu, and the terminal — at 1280 and below the breakpoints each changes at,
/// in both languages. Settings runs past the window, so its pictures are tall
/// enough to hold every group.
enum SettingsScenarios {
    static var all: [PreviewScenario] { settings + settingsStates + users + terminal }

    private static let tall: CGFloat = 1480

    private static var settings: [PreviewScenario] {
        [
            PreviewScenario(name: "settings", route: .settings, height: tall),
            PreviewScenario(name: "settings-member", route: .settings, height: tall, account: .signedOut,
                            settle: .seconds(1), setup: signInAsMember),
            PreviewScenario(name: "settings-900", route: .settings, width: 900, height: tall),
            PreviewScenario(name: "settings-600", route: .settings, width: 600, height: 1600),
            PreviewScenario(name: "settings-member-600", route: .settings, width: 600, height: 1600,
                            account: .signedOut, settle: .seconds(1), setup: signInAsMember),
            PreviewScenario(name: "settings-zh", route: .settings, height: tall, language: .zhHans),
            PreviewScenario(name: "settings-600-zh", route: .settings, width: 600, height: 1600, language: .zhHans)
        ]
    }

    /// Every state a row or a dialog of Settings can be in.
    private static var settingsStates: [PreviewScenario] {
        [
            PreviewScenario(name: "settings-change-password", route: .settings, stage: "settings.change-password",
                            account: .signedOut, settle: .seconds(1), setup: signInAsMember),
            PreviewScenario(name: "settings-change-password-error", route: .settings,
                            stage: "settings.change-password-error", account: .signedOut, settle: .seconds(2),
                            setup: signInAsMember),
            PreviewScenario(name: "settings-change-password-600", route: .settings, width: 600, height: 900,
                            stage: "settings.change-password", account: .signedOut, settle: .seconds(1),
                            setup: signInAsMember),
            PreviewScenario(name: "settings-sign-out", route: .settings, stage: "settings.sign-out"),
            PreviewScenario(name: "settings-notify-on", route: .settings, setup: { context in
                context.model.settings.notificationsEnabled = true
            }),
            PreviewScenario(name: "settings-notify-blocked", route: .settings, stage: "settings.notify-blocked"),
            PreviewScenario(name: "settings-polish-on", route: .settings, height: tall, stage: "settings.polish-on",
                            settle: .seconds(1)),
            PreviewScenario(name: "settings-polish-menu", route: .settings, height: 1100,
                            stage: "settings.polish-menu", settle: .seconds(1)),
            PreviewScenario(name: "settings-polish-unavailable", route: .settings, height: tall,
                            stage: "settings.polish-unavailable"),
            PreviewScenario(name: "settings-no-transcription", route: .settings, height: tall,
                            stage: "settings.no-transcription"),
            PreviewScenario(name: "settings-old-gateway", route: .settings, stage: "settings.old-gateway")
        ]
    }

    private static var users: [PreviewScenario] {
        [
            PreviewScenario(name: "users", route: .users),
            PreviewScenario(name: "users-900", route: .users, width: 900),
            PreviewScenario(name: "users-600", route: .users, width: 600),
            PreviewScenario(name: "users-zh", route: .users, language: .zhHans),
            PreviewScenario(name: "users-menu", route: .users, stage: "users.menu"),
            PreviewScenario(name: "users-add", route: .users, stage: "users.add"),
            PreviewScenario(name: "users-add-600", route: .users, width: 600, stage: "users.add"),
            PreviewScenario(name: "users-reset", route: .users, stage: "users.reset"),
            PreviewScenario(name: "users-delete", route: .users, stage: "users.delete"),
            PreviewScenario(name: "users-member", route: .users, account: .signedOut, settle: .seconds(1),
                            setup: { context in
                                await signInAsMember(context)
                                context.model.router.replace(.users)
                            })
        ]
    }

    private static var terminal: [PreviewScenario] {
        [
            PreviewScenario(name: "terminal", settle: .seconds(2), setup: openTerminal(offered: true)),
            PreviewScenario(name: "terminal-ls", stage: "terminal.ls", settle: .seconds(2),
                            setup: openTerminal(offered: true)),
            PreviewScenario(name: "terminal-600", width: 600, settle: .seconds(2), setup: openTerminal(offered: true)),
            PreviewScenario(name: "terminal-480", width: 480, height: 760, settle: .seconds(2),
                            setup: openTerminal(offered: true)),
            PreviewScenario(name: "terminal-exited", stage: "terminal.exit", settle: .seconds(2),
                            setup: openTerminal(offered: true)),
            PreviewScenario(name: "terminal-blocked", settle: .seconds(1), setup: openTerminal(offered: false)),
            PreviewScenario(name: "terminal-zh", language: .zhHans, settle: .seconds(2),
                            setup: openTerminal(offered: true))
        ]
    }

    /// The member the mock gateway ships with and the demo's own, who has no
    /// Users row and a Change password row instead.
    @MainActor
    private static func signInAsMember(_ context: PreviewContext) async {
        let origin = context.gateway?.absoluteString ?? "https://demo.remote-control.invalid"
        await context.model.signIn(origin: origin, username: DemoFixtures.memberUsername, password: "devdevdev")
        await context.wait(timeout: .seconds(10)) { context.model.connection.hasSnapshot }
        context.model.router.replace(.settings)
    }

    /// The terminal of the device that offers one, or of the one that does not.
    private static func openTerminal(offered: Bool) -> @MainActor @Sendable (PreviewContext) async -> Void {
        { context in
            let device = context.gateway == nil
                ? (offered ? DemoFixtures.macDeviceID : DemoFixtures.ciDeviceID)
                : (offered ? "dev-mac" : "dev-ci")
            context.model.router.replace(.terminal(deviceId: device))
        }
    }
}
