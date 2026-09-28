import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    @Suite("Error wording") @MainActor
    struct ErrorWordingTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        @Test func aReplyCodeDecidesTheSentence() {
            #expect(ErrorText.text(GatewayErrorBody(code: .deviceOffline, message: "x")) == "That device is offline.")
            #expect(ErrorText.text(GatewayErrorBody(code: .badRequest, message: "Name is too long")) == "Name is too long")
            #expect(ErrorText.text(GatewayErrorBody(code: .internalError, message: "internal"), fallback: "F") == "F")
            #expect(ErrorText.text(TransportError.requestTimedOut) == "The device did not answer in time.")
            #expect(ErrorText.text(TransportError.deliveryUncertain, fallback: "F") == "F")
        }

        @Test func aConflictFromTheDeviceKeepsItsOwnWords() {
            let typing = GatewayErrorBody(code: .conflict, message: "Somebody is typing in the terminal")
            #expect(ErrorText.refusal(typing, fallback: "F") == "Somebody is typing in the terminal")
            #expect(ErrorText.text(typing) == S.errors.conflictTerminal)
        }

        @Test func aQueuedMessageAlreadyGoneSaysSo() {
            #expect(ErrorText.queueRemove(GatewayErrorBody(code: .notFound, message: "")) == S.composer.alreadySent)
            #expect(ErrorText.queueRemove(TransportError.deliveryUncertain) == S.errors.queueRemoveFailed)
        }

        @Test func accountRoutesAreWordedFromTheirCode() {
            let taken = "That username is taken."
            #expect(AccountErrors.userErrorText(TransportError.http(status: 409, code: "conflict"), conflict: taken) == taken)
            #expect(AccountErrors.userErrorText(TransportError.http(status: 400, code: "bad_request"), conflict: taken)
                    == S.account.rules)
            #expect(AccountErrors.userErrorText(TransportError.http(status: 404, code: "not_found"), conflict: taken)
                    == S.account.gone)
            #expect(AccountErrors.userErrorText(TransportError.notConnected, conflict: taken) == S.errors.generic)
        }

        @Test func signInRefusalsReadAsTheWebsDo() {
            #expect(LoginErrorText.signIn(TransportError.unauthorized) == "Wrong username or password.")
            #expect(LoginErrorText.signIn(TransportError.http(status: 403, code: "forbidden")) == "This account is disabled.")
            #expect(LoginErrorText.signIn(TransportError.http(status: 429, code: nil)) == S.login.rateLimited)
            #expect(LoginErrorText.signIn(URLError(.cannotConnectToHost)) == "Cannot reach the gateway.")
            #expect(LoginErrorText.register(TransportError.http(status: 409, code: "conflict")) == "That username is taken.")
            #expect(LoginErrorText.register(TransportError.http(status: 400, code: "bad_request")) == S.account.rules)
            #expect(LoginErrorText.register(TransportError.http(status: 403, code: "forbidden"))
                    == "Registration is closed.")
            #expect(LoginErrorText.closesRegistration(TransportError.http(status: 403, code: "forbidden")))
        }
    }
}

/// `web/tests/dotTone.test.ts`: the whole table, state by control, online and off.
@Suite("Dot tone")
struct DotToneTests {
    private let table: [String: [String: DotTone]] = [
        "starting": ["remote": .working, "terminal": .working, "shared": .working, "none": .working],
        "running": ["remote": .working, "terminal": .working, "shared": .working, "none": .working],
        "needs_approval": ["remote": .waiting, "terminal": .waiting, "shared": .waiting, "none": .waiting],
        "needs_input": ["remote": .waiting, "terminal": .waiting, "shared": .waiting, "none": .waiting],
        "idle": ["remote": .live, "terminal": .live, "shared": .live, "none": .off],
        "readonly": ["remote": .live, "terminal": .live, "shared": .live, "none": .off],
        "stopped": ["remote": .off, "terminal": .off, "shared": .off, "none": .off],
        "error": ["remote": .failed, "terminal": .failed, "shared": .failed, "none": .failed]
    ]

    @Test func everyStateAndControlOnAnOnlineDevice() {
        for (state, controls) in table {
            for (control, tone) in controls {
                #expect(DotTone.of(state: SessionState(rawValue: state), control: SessionControl(rawValue: control),
                                   online: true) == tone, "\(state)/\(control)")
                #expect(DotTone.of(state: SessionState(rawValue: state), control: SessionControl(rawValue: control),
                                   online: false) == .off)
            }
        }
    }
}
