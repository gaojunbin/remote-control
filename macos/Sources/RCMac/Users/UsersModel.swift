import Foundation
import Observation
import RCCore

/// `web/src/stores/users.ts` — A24: the accounts on this gateway as the admin
/// sees them, on RCCore's `UsersStore`. Nothing pushes accounts over the app
/// socket, so the list is whatever the last `GET /api/users` said, and every
/// write re-reads it. It lives as long as the model, as the web's store lives as
/// long as the tab, so a second visit draws the list it had while it re-reads.
@MainActor
@Observable
final class UsersModel {
    private(set) var loaded = false
    /// Why the last action that has no dialog of its own failed.
    private(set) var error: String?
    /// The switch's value while its write is out: it answers the click before
    /// the gateway does, and goes back if the gateway refuses.
    private var pendingRegistration: Bool?
    private var store: UsersStore?

    var users: [UserRecord] { store?.users ?? [] }

    var registrationOpen: Bool { pendingRegistration ?? store?.registrationOpen ?? false }

    func load(on connection: ConnectionStore) async {
        guard let store = store(on: connection) else { return }
        await store.load()
        loaded = true
        error = store.errorMessage == nil ? nil : S.users.loadFailed
    }

    func openRegistration(_ open: Bool, on connection: ConnectionStore) async {
        guard let store = store(on: connection) else { return }
        pendingRegistration = open
        error = nil
        do {
            try await store.setRegistration(open: open)
        } catch {
            self.error = S.users.registrationFailed
        }
        pendingRegistration = nil
    }

    func toggleState(of user: UserRecord, on connection: ConnectionStore) async {
        guard let store = store(on: connection) else { return }
        error = nil
        do {
            try await store.setState(user.state == .disabled ? .active : .disabled, of: user.username)
            await load(on: connection)
        } catch {
            self.error = AccountErrors.userErrorText(error, conflict: S.account.notAllowed)
        }
    }

    /// The store the list is read from and the dialogs write through, made on
    /// the connection's own credential the first time it is needed. Nil for a
    /// member, whom the gateway answers `403`.
    func store(on connection: ConnectionStore) -> UsersStore? {
        if store == nil { store = connection.usersStore() }
        return store
    }

    /// Sign-out: nothing of the previous account stays (`signOut.ts`).
    func reset() {
        store = nil
        loaded = false
        error = nil
        pendingRegistration = nil
    }
}
