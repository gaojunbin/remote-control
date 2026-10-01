package com.junbingao.remotecontrol.win.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where the app is, and the way back: the web's browser history, walked with Alt+Left and
 * Alt+Right and the mouse's back and forward buttons (`docs/DESIGN.md` § "The Windows app" →
 * **Navigation is the web's history with Windows' keys**).
 *
 * `go` is a link followed, `replace` is `navigate(…, { replace: true })`. A session the gateway
 * ended lands on the login page remembering where the app was, which is where the next sign-in
 * returns — the web keeps it as the login route's `state.from`. Sign out remembers nothing, and
 * the next sign-in lands by the landing rule.
 *
 * Its state is snapshot state, read directly by the screens, and changed on the main thread.
 */
class Router {
    var route: Route by mutableStateOf(Route.Landing)
        private set
    private val backStack = mutableStateListOf<Route>()
    private val forwardStack = mutableStateListOf<Route>()

    /** Where a sign-in goes, remembered when the account was lost. */
    var returnTo: Route? by mutableStateOf(null)
        private set

    /** Ctrl+N: open the New session drawer once the Sessions page is showing. */
    var pendingNewSession: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether this account may see the Users screen (A24): a member who asks for it is sent to
     * Sessions, as `UsersPage` sends one on the web.
     */
    internal var canSeeUsers: () -> Boolean = { false }

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    /** Follow a link: the current place goes on the back list, and the forward list is dropped, as a browser drops it. */
    fun go(next: Route) {
        val resolved = resolve(next)
        if (resolved == route) return
        backStack += route
        forwardStack.clear()
        route = resolved
    }

    /** Take the current place's slot in the history. */
    fun replace(next: Route) {
        route = resolve(next)
    }

    fun back() {
        val previous = backStack.removeLastOrNull() ?: return
        forwardStack += route
        route = previous
    }

    fun forward() {
        val next = forwardStack.removeLastOrNull() ?: return
        backStack += route
        route = next
    }

    /** Ctrl+N: go to Sessions and have it open its New session drawer. */
    fun requestNewSession() {
        pendingNewSession = true
        go(Route.Sessions)
    }

    /** The Sessions page asks once it is showing; the request is gone after. */
    fun takeNewSessionRequest(): Boolean {
        val pending = pendingNewSession
        pendingNewSession = false
        return pending
    }

    /**
     * Nobody is signed in any more: every path is the login page, and the history goes, so Back
     * never opens a page of the account that left.
     *
     * `keepingPlace` is a session the gateway ended — revoked, refused, a stored token it no longer
     * takes: the page the app was on is where the next sign-in returns, as the web's redirect to
     * the login page carries it in `state.from`. Sign out is not: the web's goes to the login page
     * with no state, so the next sign-in lands by the landing rule.
     */
    internal fun signedOut(keepingPlace: Boolean) {
        if (!keepingPlace) {
            returnTo = null
        } else if (route != Route.Login && route != Route.Landing) {
            returnTo = route
        }
        backStack.clear()
        forwardStack.clear()
        route = Route.Login
    }

    /** Somebody signed in: back to where the app was headed, or to the landing rule when it was headed nowhere. */
    internal fun signedIn() {
        val destination = returnTo ?: Route.Landing
        returnTo = null
        route = resolve(destination)
    }

    private fun resolve(next: Route): Route = if (next == Route.Users && !canSeeUsers()) Route.Sessions else next
}
