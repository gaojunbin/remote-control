import Foundation
import RCCore

extension MacAppModel {
    /// `POST /api/login` against the gateway at `origin`. The address and the
    /// username are remembered for the next launch, as the iPhone app does.
    public func signIn(origin: String, username: String, password: String) async {
        await connection.signIn(origin: origin, username: username, password: password)
        adoptAccount()
    }

    /// `POST /api/register` (A24). Registering is a sign-in, so it ends where one does.
    public func register(origin: String, username: String, password: String) async {
        await connection.register(origin: origin, username: username, password: password)
        adoptAccount()
    }

    /// What the last sign-in or registration was refused with, as the gateway
    /// said it; nil when it went through.
    public var lastSignInError: (any Error)? { recorder.lastError }

    /// The app's own settings belong to whoever just signed in, so they are
    /// re-read before any screen draws (`docs/DESIGN.md` § "Accounts").
    private func adoptAccount() {
        guard connection.isSignedIn, let endpoint = connection.endpoint else { return }
        settings.remember(origin: endpoint.origin, username: connection.username)
        attachAccount()
        router.signedIn()
    }

    /// Sign out, and empty what the account left behind — the web's
    /// `signOut.ts`: every feature's own state first (its handlers run while the
    /// connection still names the account), then the drafts, then the
    /// connection itself, which drops the token, the cached lists and every
    /// capability the next `hello` has not confirmed.
    public func signOut() async {
        guard !isSigningOut else { return }
        isSigningOut = true
        defer { isSigningOut = false }
        for handler in signOutHandlers { await handler() }
        preferences.attach(api: nil)
        preferenceSync.attach(api: nil)
        deviceUpdateErrors.removeAll()
        await drafts.clear(account: connection.account)
        await connection.signOut()
        wasSignedIn = false
        router.signedOut()
    }

    /// A session the gateway ended on its own — a token it revoked (4401), an
    /// account it refused (4403), a stored token it no longer takes — ends as
    /// the Sign out button does, which is what the web's `onUnauthorized` does.
    func followSignedIn() {
        let signedIn = withObservationTracking {
            connection.isSignedIn
        } onChange: { [weak self] in
            Task { @MainActor in self?.followSignedIn() }
        }
        if wasSignedIn && !signedIn && !isSigningOut { Task { await signOut() } }
        wasSignedIn = signedIn
    }
}
