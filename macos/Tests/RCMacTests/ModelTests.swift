import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    @Suite("Model", .serialized) @MainActor
    struct ModelTests {
        private func wait(_ condition: () -> Bool) async -> Bool {
            for _ in 0..<200 {
                if condition() { return true }
                try? await Task.sleep(for: .milliseconds(25))
            }
            return condition()
        }

        @Test func launchArgumentsAreRead() {
            let options = LaunchOptions(arguments: ["app", "--demo", "--ephemeral", "--language=zh-Hans"])
            #expect(options.demo && options.ephemeral && !options.demoAccount)
            #expect(options.language == .zhHans)
            #expect(LaunchOptions(arguments: ["app", "--language=fr"]).language == nil)
        }

        @Test func anEphemeralRunKeepsNothingOfThePersons() {
            let persistence = Persistence(ephemeral: true)
            #expect(persistence.isEphemeral)
            #expect(persistence.defaults !== UserDefaults.standard)
            #expect(persistence.secrets is MemorySecretStore)
            persistence.discard()
        }

        @Test func theDemoSignsInAndReachesItsSnapshot() async {
            let model = MacAppModel(options: LaunchOptions(demo: true, ephemeral: true))
            defer { model.discardEphemeralState() }
            await model.restoreOrPrompt()
            #expect(model.isSignedIn && model.isDemo)
            #expect(await wait { model.connection.hasSnapshot })
            #expect(!model.connection.devices.isEmpty)
            #expect(model.router.route == .landing)
            #expect(!model.isResuming)
            await model.signOut()
        }

        @Test func signingOutRunsEveryHandlerAndLandsOnTheForm() async {
            let model = MacAppModel(options: LaunchOptions(demo: true, ephemeral: true))
            defer { model.discardEphemeralState() }
            await model.restoreOrPrompt()
            _ = await wait { model.connection.hasSnapshot }
            var calls: [String] = []
            model.onSignOut { calls.append("first:\(model.isSignedIn)") }
            model.onSignOut { calls.append("second") }
            model.router.go(.settings)
            model.deviceUpdateErrors["d"] = "refused"
            await model.signOut()
            #expect(calls == ["first:true", "second"])
            #expect(!model.isSignedIn)
            #expect(model.router.route == .login)
            #expect(model.deviceUpdateErrors.isEmpty)
        }

        @Test func theFormSaysWhatTheGatewayRefused() async {
            let model = MacAppModel(options: LaunchOptions(demoAccount: true, ephemeral: true))
            defer { model.discardEphemeralState() }
            await model.restoreOrPrompt()
            let origin = "https://demo.remote-control.invalid"
            await model.signIn(origin: origin, username: "nobody", password: "wrongpass")
            #expect(!model.isSignedIn)
            #expect(LoginErrorText.signIn(model.lastSignInError) == S.login.failed)
            await model.signIn(origin: origin, username: "admin", password: "longenough")
            #expect(model.isSignedIn)
            #expect(model.lastSignInError == nil)
            #expect(model.settings.lastOrigin == origin)
            #expect(model.settings.username(for: origin) == "admin")
            await model.signOut()
        }

        @Test func theInterfaceLanguageFollowsTheSignedInAccount() async {
            let model = MacAppModel(options: LaunchOptions(demo: true, ephemeral: true))
            defer { model.discardEphemeralState() }
            await model.restoreOrPrompt()
            model.settings.language = .zhHans
            #expect(await wait { InterfaceLanguageSource.shared.current == .zhHans })
            // The login page is the web's: English, whatever the account chose.
            await model.signOut()
            #expect(await wait { InterfaceLanguageSource.shared.current == .en })
        }

        @Test func aLanguageFixedAtLaunchHoldsOnTheLoginPageToo() async {
            let model = MacAppModel(options: LaunchOptions(demoAccount: true, ephemeral: true, language: .zhHans))
            defer { model.discardEphemeralState() }
            #expect(InterfaceLanguageSource.shared.current == .zhHans)
        }

        @Test func updateRequiredIsReachedFromTheDemo() async {
            let model = MacAppModel(options: LaunchOptions(demo: true, demoUpdateRequired: true, ephemeral: true))
            defer { model.discardEphemeralState() }
            await model.restoreOrPrompt()
            #expect(await wait { model.connection.updateRequired != nil })
            #expect(model.connection.updateRequired?.minimum == AppVersion(DemoFixtures.laterAppVersion))
            await model.signOut()
            #expect(model.connection.updateRequired == nil)
        }

        @Test func aTransitionReachesEveryHandler() async {
            let model = MacAppModel(options: LaunchOptions(demo: true, ephemeral: true))
            defer { model.discardEphemeralState() }
            var seen = 0
            model.onSessionTransition { _, _ in seen += 1 }
            model.onSessionTransition { _, _ in seen += 1 }
            let session = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "", cwd: "/")
            model.connection.onSessionTransition?(session, session)
            #expect(seen == 2)
        }
    }
}

