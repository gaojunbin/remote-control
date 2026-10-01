package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.ProtocolFailure
import com.junbingao.remotecontrol.core.transport.TransportError

/**
 * One error, one sentence, whatever kind of error it is.
 *
 * Every store that shows a failure to the reader comes through here, so a transport failure, a
 * gateway `reply` error and a decoding failure read alike.
 */
object GatewayMessage {
    fun text(error: Throwable): String = when (error) {
        is TransportError -> error.errorDescription
        is GatewayErrorBody -> error.message
        is ProtocolFailure -> error.errorDescription
        else -> error.localizedDescription
    }

    /**
     * The HTTP status behind an error, where it had one. A 401 arrives as
     * `TransportError.Unauthorized` because every authenticated call treats it as the end of a
     * session; the account forms read it for their own reason.
     */
    internal fun status(of: Throwable): Int? = when (of) {
        TransportError.Unauthorized -> 401
        is TransportError.Http -> of.status
        else -> null
    }
}

/**
 * What the account forms say when the gateway refuses them (A24).
 *
 * The words are the web's words: one product, two apps, and a person who is told "That username is
 * taken" in a browser reads the same sentence on the phone. What the gateway answered decides
 * which sentence, never the app's guess at what the reader did wrong.
 *
 * One status means two things across the routes — a `409` is a taken username on
 * `POST /api/users` and an account refusing to be touched on `PATCH` — so each route has its own
 * function here rather than one mapper guessing from the code. The web splits them the same way
 * for the same reason; it keys off `error.code` where a browser can read one, while a `401`
 * reaches this side as `TransportError.Unauthorized` with the code already dropped.
 */
object AccountError {
    /** `POST /api/login`. A wrong password is never told which half was wrong. */
    fun signIn(error: Throwable): String = when (GatewayMessage.status(of = error)) {
        400 -> L10n.string("Enter a username and a password.")
        401 -> L10n.string("Wrong username or password.")
        403 -> L10n.string("This account is disabled.")
        else -> GatewayMessage.text(error)
    }

    /** `POST /api/register`. */
    fun register(error: Throwable): String = when (GatewayMessage.status(of = error)) {
        400 -> rules
        403 -> L10n.string("Registration is closed.")
        409 -> L10n.string("That username is taken.")
        else -> GatewayMessage.text(error)
    }

    /** `POST /api/password`, the caller changing their own. */
    fun passwordChange(error: Throwable): String = when (GatewayMessage.status(of = error)) {
        400 -> rules
        401 -> L10n.string("That is not your current password.")
        else -> manage(error)
    }

    /**
     * Changing an account that already exists, 3.9. A refusal here is the `admin` row — the one
     * account no state, role or password may touch — or a caller who is not an admin at all. The
     * screen offers neither, so this is what a race says.
     */
    fun manage(error: Throwable): String = when (GatewayMessage.status(of = error)) {
        400 -> rules
        403, 409 -> L10n.string("This account cannot be changed.")
        404 -> L10n.string("That account no longer exists.")
        else -> GatewayMessage.text(error)
    }

    /** Creating an account, where a 409 is a name somebody already has rather than an account refusing to be touched. */
    fun create(error: Throwable): String =
        if (GatewayMessage.status(of = error) == 409) L10n.string("That username is taken.") else manage(error)

    /**
     * Both rules in one sentence each, because one route answers `400` for a username outside the
     * rules and for a password outside them alike, and the form cannot tell which it was.
     */
    val rules: String
        get() = L10n.string(
            "Usernames are 3 to 32 characters: lower-case letters, digits, dots, underscores and hyphens. Passwords are 8 characters or more.")
}
