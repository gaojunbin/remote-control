import Testing
import Foundation
@testable import RCCore

/// The Add device sheet's code stays on screen until the gateway has taken it
/// back. Cancelling used to blank the flow first and ask afterwards, which drew
/// the "Requesting a code" placeholder on a sheet that was closing.
@Suite("Pairing flow")
struct PairingFlowTests {
    @Test("Cancel keeps the code until the gateway has taken it back")
    @MainActor
    func cancelKeepsTheCodeWhileTheGatewayIsAsked() async throws {
        let gateway = HeldGateway()
        let flow = PairingFlow(api: gateway)
        await flow.begin()
        #expect(flow.code == "RC-TEST-CODE")

        let cancelling = Task { await flow.cancel() }
        await gateway.waitForCancel()
        #expect(flow.pairing != nil, "the code is still shown while the request is out")
        #expect(flow.code == "RC-TEST-CODE")

        await gateway.releaseCancel()
        await cancelling.value
        #expect(flow.pairing == nil, "and gone once the gateway has answered")
        #expect(await gateway.cancelledCodes == ["RC-TEST-CODE"])
    }

    @Test("A claim swaps the codes without a gap")
    @MainActor
    func claimSwapsCodesWithoutAGap() async throws {
        let gateway = HeldGateway()
        let flow = PairingFlow(api: gateway)
        await flow.begin()

        let claiming = Task { try await flow.claim(token: "tok") }
        await gateway.waitForCancel()
        #expect(flow.code == "RC-TEST-CODE", "the old code stays up while it is given back")
        await gateway.releaseCancel()
        try await claiming.value
        #expect(flow.code == "RC-CLAIMED")
        #expect(flow.pairing?.install == nil, "a claimed code carries no one-liner (A23)")
    }

    @Test("Cancel with no code asks the gateway for nothing")
    @MainActor
    func cancelWithoutACodeIsANoOp() async throws {
        let gateway = HeldGateway()
        let flow = PairingFlow(api: gateway)
        await flow.cancel()
        #expect(await gateway.cancelledCodes.isEmpty)
    }
}

/// A gateway whose `cancelPairing` waits until the test lets it answer.
private actor HeldGateway: GatewayAPI {
    nonisolated let endpoint: GatewayEndpoint = try! GatewayEndpoint("https://rc.example.com")
    private(set) var cancelledCodes: [String] = []
    private var cancelWaiters: [CheckedContinuation<Void, Never>] = []
    private var arrivalWaiters: [CheckedContinuation<Void, Never>] = []
    private var cancelArrived = false

    func beginPairing() async throws -> PairingGrant {
        PairingGrant(code: "RC-TEST-CODE", expiresAt: Int64(Date().timeIntervalSince1970 * 1000) + 600_000,
                     install: InstallCommands(macos: "curl … --pair RC-TEST-CODE",
                                              linux: "curl … --pair RC-TEST-CODE"))
    }

    func cancelPairing(code: String) async throws {
        cancelledCodes.append(code)
        cancelArrived = true
        for waiter in arrivalWaiters { waiter.resume() }
        arrivalWaiters.removeAll()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            cancelWaiters.append(continuation)
        }
    }

    func claimPairingRequest(token: String) async throws -> PairingClaim {
        PairingClaim(code: "RC-CLAIMED", expiresAt: Int64(Date().timeIntervalSince1970 * 1000) + 600_000)
    }

    /// Returns once `cancelPairing` has been called and is waiting.
    func waitForCancel() async {
        if cancelArrived { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            arrivalWaiters.append(continuation)
        }
    }

    func releaseCancel() {
        for waiter in cancelWaiters { waiter.resume() }
        cancelWaiters.removeAll()
        cancelArrived = false
    }

    // Nothing below is reached by these tests; a call is a bug in one of them.
    func health() async throws -> HealthResponse { throw TransportError.notConnected }
    func login(username: String, password: String) async throws -> LoginResponse { throw TransportError.notConnected }
    func register(username: String, password: String) async throws -> LoginResponse { throw TransportError.notConnected }
    func changePassword(current: String, new: String) async throws { throw TransportError.notConnected }
    func session() async throws -> SessionInfoResponse { throw TransportError.notConnected }
    func logout() async throws {}
    func config() async throws -> GatewayConfig { throw TransportError.notConnected }
    func preferences() async throws -> PreferencesResponse { throw TransportError.notConnected }
    func patchPreferences(_ changes: PreferencePatch) async throws -> PreferencesResponse {
        throw TransportError.notConnected
    }
    func polishModels() async throws -> PolishModelsResponse { throw TransportError.notConnected }
    func polish(_ request: PolishRequest) async throws -> PolishResponse { throw TransportError.notConnected }
    func devices() async throws -> [Device] { throw TransportError.notConnected }
    func renameDevice(_ deviceID: String, name: String) async throws -> Device { throw TransportError.notConnected }
    func revokeDevice(_ deviceID: String) async throws { throw TransportError.notConnected }
    func sessions(deviceID: String?, archived: Bool?) async throws -> [Session] { throw TransportError.notConnected }
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
    func registerPush(_ registration: APNSRegistration) async throws { throw TransportError.notConnected }
    func unregisterPush(token: String) async throws { throw TransportError.notConnected }
    func restoreToken(username: String) async -> Bool { false }
    func bearerToken() async -> String? { nil }
    func forgetToken(username: String) async {}
}
