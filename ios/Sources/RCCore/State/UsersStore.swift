import Foundation
import Observation

/// The admin's accounts screen, as state (protocol 3.9, A24).
///
/// Every mutation goes to the gateway first and applies what came back, so a
/// row never shows a state the gateway did not agree to. A failure is thrown
/// rather than stored, because the sentence belongs in whichever sheet or
/// alert caused it and not at the top of the page.
@MainActor
@Observable
public final class UsersStore {
    public private(set) var users: [UserRecord] = []
    public private(set) var registrationOpen = false
    public private(set) var isLoading = false
    /// The one error the page itself owns: the list that would not load.
    public private(set) var errorMessage: String?

    @ObservationIgnored private let api: any GatewayAPI

    public init(api: any GatewayAPI) {
        self.api = api
    }

    public var hasLoaded: Bool { !users.isEmpty }

    public func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let response = try await api.users()
            guard !Task.isCancelled else { return }
            users = response.users
            registrationOpen = response.registrationOpen
            errorMessage = nil
        } catch {
            errorMessage = AccountError.manage(error)
        }
    }

    /// The switch at the top. The gateway's answer is what the switch shows,
    /// so a refusal puts it back where it was.
    public func setRegistration(open: Bool) async throws {
        registrationOpen = try await api.setRegistration(open: open)
    }

    public func create(username: String, password: String, role: UserRole) async throws {
        let record = try await api.createUser(username: username, password: password, role: role)
        apply(record)
    }

    public func resetPassword(of username: String, to password: String) async throws {
        apply(try await api.patchUser(username, state: nil, role: nil, password: password))
    }

    public func setState(_ state: UserState, of username: String) async throws {
        apply(try await api.patchUser(username, state: state, role: nil, password: nil))
    }

    public func delete(_ username: String) async throws {
        try await api.deleteUser(username)
        users.removeAll { $0.username == username }
    }

    /// Oldest first, the order `GET /api/users` promises, kept when a row is
    /// replaced and when one is added.
    private func apply(_ record: UserRecord) {
        if let index = users.firstIndex(where: { $0.username == record.username }) {
            users[index] = record
        } else {
            users.append(record)
        }
    }
}
