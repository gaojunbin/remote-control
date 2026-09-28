import Foundation
import RCCore
import Testing
@testable import RCMac

/// `web/tests/SettingsPage.test.tsx`'s rules that live outside a view: the
/// header's dot and host, and what the password dialog says.
@Suite("Settings header") @MainActor
struct SettingsHeaderTests {
    @Test func theDotTakesItsToneFromTheSocketAlone() {
        // The web's `open` is a socket up, whether or not its hello has landed.
        #expect(IdentityDot.tone(.connected) == .working)
        #expect(IdentityDot.tone(.syncing) == .working)
        #expect(IdentityDot.tone(.connecting) == .waiting)
        #expect(IdentityDot.tone(.reconnecting) == .waiting)
        #expect(IdentityDot.tone(.signedOut) == .off)
        // The ruling's red, which a browser never reaches and this app does.
        #expect(IdentityDot.tone(.superseded) == .failed)
        #expect(IdentityDot.tone(.incompatible(gatewayVersion: 2)) == .failed)
        #expect(IdentityDot.tone(.expired) == .failed)
    }

    @Test func aLongHostLosesItsMiddleAndKeepsItsPort() {
        #expect(MiddleTruncatedHost.split("rc.example.com:8443") == ("rc.example.", "com:8443"))
        #expect(MiddleTruncatedHost.split("127.0.0.1:5173") == ("127.0.", "0.1:5173"))
        #expect(MiddleTruncatedHost.split("demo") == ("", "demo"))
    }

    @Test func aDialogIsOpenExactlyWhileItHoldsAForm() {
        var form: ChangePasswordForm?
        #expect(!form.settingsModalOpen)
        form = ChangePasswordForm()
        #expect(form.settingsModalOpen)
        form.settingsModalOpen = false
        #expect(form == nil)
    }
}

extension LanguageSensitive {
    @Suite("Settings words", .serialized) @MainActor
    struct SettingsWordsTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        @Test func theDotsWordIsItsLabelAlone() {
            #expect(IdentityDot.word(.connected) == "Connected")
            #expect(IdentityDot.word(.reconnecting) == "Connecting")
            #expect(IdentityDot.word(.signedOut) == "Offline")
            #expect(IdentityDot.word(.superseded) == "Refused")
            InterfaceLanguageSource.shared.current = .zhHans
            #expect(IdentityDot.word(.connected) == "已连接")
            #expect(IdentityDot.word(.superseded) == "已拒绝")
            InterfaceLanguageSource.shared.current = .en
        }

        @Test func aPasswordRefusalIsWordedFromItsStatus() {
            #expect(ChangePasswordForm.errorText(TransportError.unauthorized) == "That is not your current password.")
            #expect(ChangePasswordForm.errorText(TransportError.http(status: 403, code: "forbidden"))
                    == S.account.notAllowed)
            #expect(ChangePasswordForm.errorText(TransportError.http(status: 400, code: "bad_request"))
                    == S.account.rules)
            #expect(ChangePasswordForm.errorText(TransportError.http(status: 500, code: nil)) == S.errors.generic)
            #expect(ChangePasswordForm.errorText(URLError(.timedOut)) == S.errors.generic)
        }

        @Test func thePasswordDialogWaitsForBothFields() {
            let form = ChangePasswordForm()
            #expect(!form.ready)
            form.current = "old"
            form.next = "short"
            #expect(!form.ready)
            form.next = "long enough"
            #expect(form.ready)
            form.current = ""
            #expect(!form.ready)
        }

        @Test func aMemberChangesItsOwnPasswordAndIsToldWhenTheOldOneIsWrong() async throws {
            let model = MacAppModel(options: LaunchOptions(demoAccount: true, ephemeral: true))
            defer { model.discardEphemeralState() }
            await model.restoreOrPrompt()
            await model.signIn(origin: "https://demo.remote-control.invalid", username: DemoFixtures.memberUsername,
                               password: "devdevdev")
            #expect(model.isSignedIn && !model.connection.isAdmin)
            let refused = ChangePasswordForm()
            refused.current = "short"
            refused.next = "another-password"
            #expect(await refused.submit(on: model.connection) == false)
            #expect(refused.error == "That is not your current password.")
            #expect(!refused.busy)
            let taken = ChangePasswordForm()
            taken.current = "devdevdev"
            taken.next = "another-password"
            #expect(await taken.submit(on: model.connection))
            await model.signOut()
        }
    }
}
