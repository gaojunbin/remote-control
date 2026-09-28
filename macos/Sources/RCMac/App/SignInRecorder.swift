import Foundation
import RCCore
import Synchronization

/// The error the last sign-in or registration ended with, kept as the gateway
/// sent it.
///
/// `ConnectionStore` words a refusal in the iPhone app's sentences before any
/// screen sees it; the login page says the web's instead, which are chosen by
/// status (`signInErrorText` and `registerErrorText` in `LoginPage.tsx`). This
/// is the Mac-side adapter that keeps the status: every API the store builds is
/// wrapped by `RecordingGatewayAPI`, which notes what `login` and `register`
/// threw.
public final class SignInRecorder: Sendable {
    private let last = Mutex<(any Error)?>(nil)

    public init() {}

    public var lastError: (any Error)? { last.withLock { $0 } }

    func record(_ error: (any Error)?) { last.withLock { $0 = error } }
}

/// A `GatewayAPI` that passes every call to the one it wraps and notes how a
/// sign-in or a registration ended.
struct RecordingGatewayAPI: GatewayAPI {
    let base: any GatewayAPI
    let recorder: SignInRecorder

    var endpoint: GatewayEndpoint { base.endpoint }

    func login(username: String, password: String) async throws -> LoginResponse {
        try await recording { try await base.login(username: username, password: password) }
    }

    func register(username: String, password: String) async throws -> LoginResponse {
        try await recording { try await base.register(username: username, password: password) }
    }

    private func recording(_ call: () async throws -> LoginResponse) async throws -> LoginResponse {
        do {
            let response = try await call()
            recorder.record(nil)
            return response
        } catch {
            recorder.record(error)
            throw error
        }
    }

    func health() async throws -> HealthResponse { try await base.health() }
    func changePassword(current: String, new: String) async throws {
        try await base.changePassword(current: current, new: new)
    }
    func session() async throws -> SessionInfoResponse { try await base.session() }
    func logout() async throws { try await base.logout() }
    func config() async throws -> GatewayConfig { try await base.config() }
    func preferences() async throws -> PreferencesResponse { try await base.preferences() }
    func patchPreferences(_ changes: PreferencePatch) async throws -> PreferencesResponse {
        try await base.patchPreferences(changes)
    }
    func polishModels() async throws -> PolishModelsResponse { try await base.polishModels() }
    func polish(_ request: PolishRequest) async throws -> PolishResponse { try await base.polish(request) }
    func devices() async throws -> [Device] { try await base.devices() }
    func renameDevice(_ deviceID: String, name: String) async throws -> Device {
        try await base.renameDevice(deviceID, name: name)
    }
    func revokeDevice(_ deviceID: String) async throws { try await base.revokeDevice(deviceID) }
    func beginPairing() async throws -> PairingGrant { try await base.beginPairing() }
    func cancelPairing(code: String) async throws { try await base.cancelPairing(code: code) }
    func claimPairingRequest(token: String) async throws -> PairingClaim {
        try await base.claimPairingRequest(token: token)
    }
    func sessions(deviceID: String?, archived: Bool?) async throws -> [Session] {
        try await base.sessions(deviceID: deviceID, archived: archived)
    }
    func users() async throws -> UserListResponse { try await base.users() }
    func createUser(username: String, password: String, role: UserRole) async throws -> UserRecord {
        try await base.createUser(username: username, password: password, role: role)
    }
    func patchUser(_ username: String, state: UserState?, role: UserRole?,
                   password: String?) async throws -> UserRecord {
        try await base.patchUser(username, state: state, role: role, password: password)
    }
    func deleteUser(_ username: String) async throws { try await base.deleteUser(username) }
    func setRegistration(open: Bool) async throws -> Bool { try await base.setRegistration(open: open) }
    func registerPush(_ registration: APNSRegistration) async throws { try await base.registerPush(registration) }
    func unregisterPush(token: String) async throws { try await base.unregisterPush(token: token) }
    func restoreToken(username: String) async -> Bool { await base.restoreToken(username: username) }
    func bearerToken() async -> String? { await base.bearerToken() }
    func forgetToken(username: String) async { await base.forgetToken(username: username) }
}
