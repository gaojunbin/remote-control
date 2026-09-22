import Testing
import Foundation
@testable import RCCore

/// Amendment A41: the Settings screen's preferences are the account's.
///
/// The gateway keeps them, `SettingsStore` caches them, and `PreferenceSync`
/// keeps the two equal: what arrives is applied, what the person changes is
/// written up, a field the account has never been told is offered this phone's
/// value once, and nothing that arrives is ever echoed back.
@Suite("Amendment A41, the Settings preferences are the account's")
struct PreferenceSyncTests {
    /// A store nobody else's run can reach, and nothing to inherit.
    @MainActor
    private func store() -> SettingsStore {
        SettingsStore(defaults: UserDefaults(suiteName: "rc-a41-\(UUID().uuidString)")!)
    }

    @MainActor
    private func hello(_ preferences: Preferences?) -> AppFrame {
        .hello(HelloFrame(protocolVersion: RemoteProtocol.version, gatewayVersion: "test",
                          user: UserIdentity(username: "me"), devices: [], sessions: [],
                          stt: .disabled, preferences: preferences, serverTime: 0))
    }

    @Test("The account's values are the app's the moment hello carries them")
    @MainActor
    func helloIsApplied() async throws {
        let settings = store()
        let gateway = RecordingGateway()
        let sync = PreferenceSync(settings: settings)
        sync.attach(api: gateway)

        sync.receive(hello(Preferences(resumeAfterLimit: true, language: .zhHans,
                                       sttLanguage: "zh", polishEnabled: true,
                                       polishModel: "gpt-5.4-mini", polishStrength: .strong,
                                       timelineDetail: .detailed)))
        #expect(settings.language == .zhHans)
        #expect(settings.voiceLanguage == "zh")
        #expect(settings.polishEnabled)
        #expect(settings.polishModel == "gpt-5.4-mini")
        #expect(settings.polishStrength == .strong)
        #expect(settings.timelineDetail == .detailed)

        await sync.settle()
        #expect(await gateway.writes.isEmpty,
                "an account that has every field is asked for nothing")
    }

    @Test("A change made elsewhere moves the control and is not echoed back")
    @MainActor
    func updatedFrameIsAppliedOnce() async throws {
        let settings = store()
        let gateway = RecordingGateway()
        let sync = PreferenceSync(settings: settings)
        sync.attach(api: gateway)
        sync.receive(hello(Preferences(resumeAfterLimit: false, language: .en, sttLanguage: "auto",
                                       polishEnabled: false, polishModel: "",
                                       polishStrength: .moderate, timelineDetail: .simple)))

        sync.receive(.preferencesUpdated(Preferences(resumeAfterLimit: false, language: .en,
                                                     sttLanguage: "auto", polishEnabled: true,
                                                     polishModel: "", polishStrength: .moderate,
                                                     timelineDetail: .detailed)))
        #expect(settings.polishEnabled, "the switch another app turned on is on here")
        #expect(settings.timelineDetail == .detailed, "and the detail changed with it")

        await sync.settle()
        #expect(await gateway.writes.isEmpty, "with nothing written back for either of them")
    }

    @Test("A field the account has none of is offered this phone's value, once")
    @MainActor
    func ownValuesAreOfferedOnce() async throws {
        let settings = store()
        settings.timelineDetail = .detailed
        settings.polishModel = "gpt-4.1"
        let gateway = RecordingGateway()
        let sync = PreferenceSync(settings: settings)
        sync.attach(api: gateway)

        // The account arrived at A41 with the resume switch and nothing else.
        sync.receive(hello(Preferences(resumeAfterLimit: true)))
        await sync.settle()
        let offered = try #require(await gateway.writes.first)
        #expect(offered.timelineDetail == .detailed, "this phone's detail becomes the account's")
        #expect(offered.polishModel == "gpt-4.1", "and its model")
        #expect(offered.language == .en, "and every other field the account had none of")
        #expect(offered.sttLanguage == "auto")
        #expect(offered.polishEnabled == false)
        #expect(offered.polishStrength == .moderate)
        #expect(offered.resumeAfterLimit == nil, "never the switch, which the account already had")
        #expect(await gateway.writes.count == 1, "one write, not one per field")

        // A second connection of the same sign-in asks for nothing again.
        sync.receive(hello(await gateway.held))
        await sync.settle()
        #expect(await gateway.writes.count == 1, "and the offer is made once, not once a flap")
    }

    @Test("A change made on this phone is written up and the reply is the value")
    @MainActor
    func aLocalChangeIsWrittenUp() async throws {
        let settings = store()
        let gateway = RecordingGateway()
        let sync = PreferenceSync(settings: settings)
        sync.attach(api: gateway)
        sync.receive(hello(Preferences(resumeAfterLimit: false, language: .en, sttLanguage: "auto",
                                       polishEnabled: false, polishModel: "",
                                       polishStrength: .moderate, timelineDetail: .simple)))

        settings.polishEnabled = true
        await sync.settle()
        #expect(await gateway.writes.count == 1, "the change goes up on its own")
        #expect(await gateway.writes.first?.polishEnabled == true, "carrying that field")
        #expect(await gateway.writes.first?.language == nil, "and no field nobody touched")
        #expect(await gateway.held.polishEnabled == true, "so the account now holds it")
        #expect(settings.polishEnabled, "and the screen reads what the gateway answered")
    }

    @Test("Two changes a moment apart reach the gateway in the order they were made")
    @MainActor
    func writesKeepTheirOrder() async throws {
        let settings = store()
        let gateway = RecordingGateway()
        let sync = PreferenceSync(settings: settings)
        sync.attach(api: gateway)
        sync.receive(hello(Preferences(resumeAfterLimit: false, language: .en, sttLanguage: "auto",
                                       polishEnabled: false, polishModel: "",
                                       polishStrength: .moderate, timelineDetail: .simple)))

        // The second change is made while the first is still out. Racing them
        // would let the older one be the last to arrive, and the gateway takes
        // the order they arrive in as the order of truth.
        await gateway.hold()
        settings.timelineDetail = .detailed
        await gateway.waitForWrite()
        settings.polishEnabled = true
        await gateway.release()
        await sync.settle()

        #expect(await gateway.writes.count == 2, "one request each, never both at once")
        #expect(await gateway.writes.first?.timelineDetail == .detailed, "the first change first")
        #expect(await gateway.writes.last?.polishEnabled == true, "and the second behind it")
        #expect(await gateway.writes.last?.timelineDetail == nil,
                "carrying only what the first one did not settle")
        #expect(await gateway.held.timelineDetail == .detailed, "so the account holds both")
        #expect(await gateway.held.polishEnabled == true)
    }

    @Test("A change the person undid before the round trip is never written")
    @MainActor
    func aChangeThatNetsToNothingIsNotWritten() async throws {
        let settings = store()
        let gateway = RecordingGateway()
        let sync = PreferenceSync(settings: settings)
        sync.attach(api: gateway)
        sync.receive(hello(Preferences(resumeAfterLimit: false, language: .en, sttLanguage: "auto",
                                       polishEnabled: false, polishModel: "",
                                       polishStrength: .moderate, timelineDetail: .simple)))

        settings.timelineDetail = .detailed
        settings.timelineDetail = .simple
        await sync.settle()
        #expect(await gateway.writes.isEmpty, "the account already reads what the screen does")
        #expect(settings.timelineDetail == .simple)
    }

    @Test("A gateway that carries no preferences leaves this phone's settings alone")
    @MainActor
    func anOlderGatewayChangesNothing() async throws {
        let settings = store()
        settings.timelineDetail = .detailed
        settings.polishEnabled = true
        let gateway = RecordingGateway()
        let sync = PreferenceSync(settings: settings)
        sync.attach(api: gateway)

        sync.receive(hello(nil))
        await sync.settle()
        #expect(settings.timelineDetail == .detailed, "nothing it had is taken away")
        #expect(settings.polishEnabled)
        #expect(await gateway.writes.isEmpty, "and a gateway that offers none is asked for none")
    }

    @Test("Signing out forgets the account's copy")
    @MainActor
    func signingOutForgetsTheAccount() async throws {
        let settings = store()
        let gateway = RecordingGateway()
        let sync = PreferenceSync(settings: settings)
        sync.attach(api: gateway)
        sync.receive(hello(Preferences(resumeAfterLimit: false, language: .en, sttLanguage: "auto",
                                       polishEnabled: false, polishModel: "",
                                       polishStrength: .moderate, timelineDetail: .simple)))

        sync.attach(api: nil)
        settings.polishEnabled = true
        await sync.settle()
        #expect(await gateway.writes.isEmpty, "a signed-out app writes nobody's preferences")
    }
}

/// A gateway that keeps the account's preferences the way a real one does and
/// remembers every write, so a test can count them as well as read them. The
/// next write can be held on arrival, which is how the order of two of them is
/// looked at rather than raced.
private actor RecordingGateway: GatewayAPI {
    nonisolated let endpoint: GatewayEndpoint = try! GatewayEndpoint("https://rc.example.com")
    private(set) var writes: [PreferencePatch] = []
    private(set) var held = Preferences()
    private var holdsNextWrite = false
    private var holding: CheckedContinuation<Void, Never>?
    private var arrivals: [CheckedContinuation<Void, Never>] = []

    func preferences() async throws -> PreferencesResponse { PreferencesResponse(preferences: held) }

    func patchPreferences(_ changes: PreferencePatch) async throws -> PreferencesResponse {
        if holdsNextWrite {
            holdsNextWrite = false
            for waiter in arrivals { waiter.resume() }
            arrivals.removeAll()
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                holding = continuation
            }
        }
        writes.append(changes)
        held = held.applying(changes)
        return PreferencesResponse(preferences: held)
    }

    /// Hold the next write until the test lets it answer.
    func hold() { holdsNextWrite = true }

    /// Returns once that write has arrived and is waiting.
    func waitForWrite() async {
        if holding != nil { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            arrivals.append(continuation)
        }
    }

    func release() {
        holding?.resume()
        holding = nil
    }

    // Nothing below is reached by these tests; a call is a bug in one of them.
    func health() async throws -> HealthResponse { throw TransportError.notConnected }
    func login(username: String, password: String) async throws -> LoginResponse {
        throw TransportError.notConnected
    }
    func register(username: String, password: String) async throws -> LoginResponse {
        throw TransportError.notConnected
    }
    func changePassword(current: String, new: String) async throws {
        throw TransportError.notConnected
    }
    func session() async throws -> SessionInfoResponse { throw TransportError.notConnected }
    func logout() async throws {}
    func config() async throws -> GatewayConfig { throw TransportError.notConnected }
    func polishModels() async throws -> PolishModelsResponse { throw TransportError.notConnected }
    func polish(_ request: PolishRequest) async throws -> PolishResponse {
        throw TransportError.notConnected
    }
    func devices() async throws -> [Device] { throw TransportError.notConnected }
    func renameDevice(_ deviceID: String, name: String) async throws -> Device {
        throw TransportError.notConnected
    }
    func revokeDevice(_ deviceID: String) async throws { throw TransportError.notConnected }
    func beginPairing() async throws -> PairingGrant { throw TransportError.notConnected }
    func cancelPairing(code: String) async throws { throw TransportError.notConnected }
    func claimPairingRequest(token: String) async throws -> PairingClaim {
        throw TransportError.notConnected
    }
    func sessions(deviceID: String?, archived: Bool?) async throws -> [Session] {
        throw TransportError.notConnected
    }
    func users() async throws -> UserListResponse { throw TransportError.notConnected }
    func createUser(username: String, password: String, role: UserRole) async throws -> UserRecord {
        throw TransportError.notConnected
    }
    func patchUser(_ username: String, state: UserState?, role: UserRole?,
                   password: String?) async throws -> UserRecord {
        throw TransportError.notConnected
    }
    func deleteUser(_ username: String) async throws { throw TransportError.notConnected }
    func setRegistration(open: Bool) async throws -> Bool { throw TransportError.notConnected }
    func registerPush(_ registration: APNSRegistration) async throws {
        throw TransportError.notConnected
    }
    func unregisterPush(token: String) async throws { throw TransportError.notConnected }
    func restoreToken(username: String) async -> Bool { false }
    func bearerToken() async -> String? { nil }
    func forgetToken(username: String) async {}
}
