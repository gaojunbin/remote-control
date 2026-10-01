package com.junbingao.remotecontrol.win.login

import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.strings.S

/**
 * What a refused sign-in or registration says: the web's sentences (`signInErrorText` and
 * `registerErrorText` in `LoginPage.tsx`), chosen by the status the gateway answered with. A
 * failure that never reached the gateway is "Cannot reach the gateway."
 */
object LoginErrorText {
    fun signIn(error: Throwable?): String = when (status(error)) {
        429 -> S.login.rateLimited
        403 -> S.account.disabled
        401 -> S.login.failed
        null -> if (error == null) S.errors.generic else S.login.unreachable
        else -> S.errors.generic
    }

    fun register(error: Throwable?): String = when (status(error)) {
        429 -> S.login.rateLimited
        409 -> S.account.taken
        403 -> S.login.registrationClosed
        400 -> S.account.rules
        null -> if (error == null) S.errors.generic else S.login.unreachable
        else -> S.errors.generic
    }

    /** A `403` on registering also means the gateway takes no registrations now, so the link to the form goes. */
    fun closesRegistration(error: Throwable?): Boolean = status(error) == 403

    /** The HTTP status of a refusal, where the gateway answered at all. The core reports a `401` as `Unauthorized`, with its status already read. */
    private fun status(error: Throwable?): Int? = when (error) {
        TransportError.Unauthorized -> 401
        is TransportError.Http -> error.status
        else -> null
    }
}
