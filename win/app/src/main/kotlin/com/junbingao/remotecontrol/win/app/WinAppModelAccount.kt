package com.junbingao.remotecontrol.win.app

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch

// The Mac's `MacAppModel+Account.swift`: signing in, registering and signing out.

/**
 * `POST /api/login` against the gateway at `origin`. The address and the username are remembered
 * for the next launch, as the iPhone app does.
 */
suspend fun WinAppModel.signIn(origin: String, username: String, password: String) {
    connection.signIn(origin = origin, username = username, password = password)
    adoptAccount()
}

/** `POST /api/register` (A24). Registering is a sign-in, so it ends where one does. */
suspend fun WinAppModel.register(origin: String, username: String, password: String) {
    connection.register(origin = origin, username = username, password = password)
    adoptAccount()
}

/** What the last sign-in or registration was refused with, as the gateway said it; null when it went through. */
val WinAppModel.lastSignInError: Throwable? get() = recorder.lastError

/**
 * The app's own settings belong to whoever just signed in, so they are re-read before any screen
 * draws (`docs/DESIGN.md` § "Accounts").
 */
private fun WinAppModel.adoptAccount() {
    val endpoint = connection.endpoint
    if (!connection.isSignedIn || endpoint == null) return
    settings.remember(origin = endpoint.origin, username = connection.username)
    attachAccount()
    router.signedIn()
}

/**
 * Sign out, and empty what the account left behind — the web's `signOut.ts`: every feature's own
 * state first (its handlers run while the connection still names the account), then the drafts,
 * then the connection itself, which drops the token, the cached lists and every capability the
 * next `hello` has not confirmed. The next sign-in lands by the landing rule, wherever this one
 * left from.
 */
suspend fun WinAppModel.signOut() {
    endSession(keepingPlace = false)
}

/** Sign out, for the person or for the gateway: a session the gateway ended keeps the page the app was on for the next sign-in (`Router`). */
internal suspend fun WinAppModel.endSession(keepingPlace: Boolean) {
    if (isSigningOut) return
    isSigningOut = true
    try {
        for (handler in signOutHandlers.toList()) handler()
        preferences.attach(api = null)
        preferenceSync.attach(api = null)
        deviceUpdateErrors = emptyMap()
        drafts.clear(account = connection.account)
        connection.signOut()
        wasSignedIn = false
        router.signedOut(keepingPlace = keepingPlace)
    } finally {
        isSigningOut = false
    }
}

/**
 * A session the gateway ended on its own — a token it revoked (4401), an account it refused (4403),
 * a stored token it no longer takes — ends as the Sign out button does, which is what the web's
 * `onUnauthorized` does, except that the next sign-in returns to where the app was.
 */
internal fun WinAppModel.followSignedIn() {
    wasSignedIn = connection.isSignedIn
    tasks.launch {
        snapshotFlow { connection.isSignedIn }.collect { signedIn ->
            if (wasSignedIn && !signedIn && !isSigningOut) launch { endSession(keepingPlace = true) }
            wasSignedIn = signedIn
        }
    }
}
