import Foundation
import Observation

/// Where the app is, and the way back: the web's browser history, walked with
/// ⌘[ and ⌘] (`docs/DESIGN.md` § "The Mac app" → **Navigation is the web's
/// history**).
///
/// `go` is a link followed, `replace` is `navigate(…, { replace: true })`.
/// Signing out lands on the login page and remembers where the app was, which
/// is where a sign-in returns to — the web keeps it as the login route's
/// `state.from`.
@MainActor
@Observable
public final class Router {
    public private(set) var route: Route = .landing
    private var backStack: [Route] = []
    private var forwardStack: [Route] = []
    /// Where a sign-in goes, remembered when the account was lost.
    public private(set) var returnTo: Route?
    /// ⌘N: open the New session drawer once the Sessions page is showing.
    public private(set) var pendingNewSession = false

    /// Whether this account may see the Users screen (A24): a member who asks
    /// for it is sent to Sessions, as `UsersPage` sends one on the web.
    @ObservationIgnored var canSeeUsers: @MainActor () -> Bool = { false }

    public init() {}

    public var canGoBack: Bool { !backStack.isEmpty }
    public var canGoForward: Bool { !forwardStack.isEmpty }

    /// Follow a link: the current place goes on the back list, and the forward
    /// list is dropped, as a browser drops it.
    public func go(_ next: Route) {
        let resolved = resolve(next)
        guard resolved != route else { return }
        backStack.append(route)
        forwardStack.removeAll()
        route = resolved
    }

    /// Take the current place's slot in the history.
    public func replace(_ next: Route) {
        route = resolve(next)
    }

    public func back() {
        guard let previous = backStack.popLast() else { return }
        forwardStack.append(route)
        route = previous
    }

    public func forward() {
        guard let next = forwardStack.popLast() else { return }
        backStack.append(route)
        route = next
    }

    /// ⌘N: go to Sessions and have it open its New session drawer.
    public func requestNewSession() {
        pendingNewSession = true
        go(.sessions)
    }

    /// The Sessions page asks once it is showing; the request is gone after.
    public func takeNewSessionRequest() -> Bool {
        defer { pendingNewSession = false }
        return pendingNewSession
    }

    /// Nobody is signed in any more: every path is the login page, and the one
    /// the app was on is where the next sign-in returns. The history goes, so
    /// Back never opens a page of the account that left.
    func signedOut() {
        if route != .login && route != .landing { returnTo = route }
        backStack.removeAll()
        forwardStack.removeAll()
        route = .login
    }

    /// Somebody signed in: back to where the app was headed, or to the landing
    /// rule when it was headed nowhere.
    func signedIn() {
        let destination = returnTo ?? .landing
        returnTo = nil
        route = resolve(destination)
    }

    private func resolve(_ next: Route) -> Route {
        next == .users && !canSeeUsers() ? .sessions : next
    }
}
