import Foundation
import RCCore
import Testing
@testable import RCMac

/// Dictation reaches the gateway through the HTTP client behind the store's
/// API. Every API the app builds is wrapped (`RecordingGatewayAPI`), and a
/// dictation that cast the store's API straight to `GatewayHTTPClient` found
/// nothing and ended the moment it started with "Transcription failed.".
@Suite("Voice reaches the gateway")
struct VoiceGatewayTests {
    @Test func theClientBehindTheWrapperIsTheOneFound() throws {
        let client = GatewayHTTPClient(endpoint: try GatewayEndpoint("https://rc.example.com"),
                                       secrets: MemorySecretStore())
        let wrapped = RecordingGatewayAPI(base: client, recorder: SignInRecorder())
        #expect(ConnectionFactory.httpClient(behind: wrapped) === client)
        #expect(ConnectionFactory.httpClient(behind: client) === client)
        #expect(ConnectionFactory.httpClient(behind: nil) == nil)
        #expect(ConnectionFactory.httpClient(behind: DemoGateway()) == nil)
    }

    /// The composer's own wiring against a gateway: sign in as the app does,
    /// open the dictation socket the composer opens, speak into it, and hear a
    /// partial and the final transcript back. It needs a gateway, so it runs
    /// only when `RC_MOCK_GATEWAY` names one — the web's mock gateway
    /// (`cd web && npm run mock`, `RC_MOCK_GATEWAY=http://127.0.0.1:8787`).
    @MainActor
    @Test(.enabled(if: ProcessInfo.processInfo.environment["RC_MOCK_GATEWAY"] != nil))
    func aDictationOnAGatewayHearsItsTranscript() async throws {
        let origin = try #require(ProcessInfo.processInfo.environment["RC_MOCK_GATEWAY"])
        let model = MacAppModel(options: LaunchOptions(ephemeral: true))
        defer { model.discardEphemeralState() }
        await model.signIn(origin: origin, username: "admin", password: "dev")
        #expect(model.isSignedIn)
        #expect(model.httpClient != nil, "the dictation socket has a client to authenticate with")

        let heard = HeardEvents()
        let stream = AppComposerHost.gatewaySpeech(model: model).socket { heard.events.append($0) }
        try await stream.start()
        // Half a second of silence every 100 ms, for the mock's two-second partial.
        for _ in 0..<25 {
            stream.append(Data(count: 3200))
            try await Task.sleep(for: .milliseconds(100))
        }
        stream.stop()
        let deadline = ContinuousClock.now + .seconds(5)
        while !heard.events.contains(where: { if case .final = $0 { true } else { false } }),
              ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        #expect(heard.events.contains { if case .partial = $0 { true } else { false } })
        #expect(heard.events.contains { if case .final(let text) = $0 { !text.isEmpty } else { false } })
        #expect(!heard.events.contains { if case .failed = $0 { true } else { false } })
        await model.signOut()
    }
}

/// What a dictation socket reported, in order.
@MainActor
private final class HeardEvents {
    var events: [SpeechEvent] = []
}
