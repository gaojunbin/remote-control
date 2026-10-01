package com.junbingao.remotecontrol.win.shared

import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.strings.S

/**
 * `web/src/lib/errors.ts`: a rejected gateway request, as a sentence the reader can act on. The
 * words are read when the failure happens, never stored: a sentence captured earlier would keep
 * the language it was built in.
 */
object ErrorText {
    /** The web's sentence for a reply's `error.code`, where it has one. */
    internal fun byCode(code: String): String? = when (code) {
        GatewayErrorCode.notFound.rawValue -> S.errors.notFound
        GatewayErrorCode.deviceOffline.rawValue -> S.errors.deviceOffline
        GatewayErrorCode.conflict.rawValue -> S.errors.conflictTerminal
        GatewayErrorCode.timeout.rawValue -> S.errors.timeout
        GatewayErrorCode.unsupported.rawValue -> S.errors.unsupported
        GatewayErrorCode.tooLarge.rawValue -> S.errors.tooLarge
        else -> null
    }

    /**
     * The code decides the sentence. A locally minted failure — the socket is not open, the gateway
     * never answered — carries no message of its own, so it falls through to the caller's fallback
     * rather than reaching the screen as an empty line or as a transport's English.
     */
    fun text(error: Throwable, fallback: String = S.errors.generic): String {
        if (error is GatewayErrorBody) {
            byCode(error.code.rawValue)?.let { return it }
            // A reply with no message of its own decodes with its code as the message, which is not a sentence.
            return if (error.message.isEmpty() || error.message == error.code.rawValue) fallback else error.message
        }
        return when (error) {
            TransportError.RequestTimedOut -> S.errors.timeout
            is TransportError.Http -> error.code?.let(::byCode) ?: fallback
            else -> fallback
        }
    }

    /**
     * A40: what `session.set` and `session.command` say when they are refused. A `conflict` from
     * either is the device explaining why it could not reach the session — the terminal it types
     * into is running a turn, or somebody is typing there — and only the device knows which. Its
     * sentence is shown as it arrived; the canned one, about taking the session over, would be
     * wrong. Every other code keeps the app's own words.
     */
    fun refusal(error: Throwable, fallback: String): String {
        if (error is GatewayErrorBody && error.code == GatewayErrorCode.conflict &&
            error.message.isNotEmpty() && error.message != error.code.rawValue
        ) {
            return error.message
        }
        return text(error, fallback = fallback)
    }

    /**
     * A43: what a refused `session.queue_remove` says. `not_found` means the device delivered the
     * message before the request reached it, for a Remove as for an edit, so the sentence says that
     * and never the bare code.
     */
    fun queueRemove(error: Throwable): String {
        if (error is GatewayErrorBody && error.code == GatewayErrorCode.notFound) return S.composer.alreadySent
        return text(error, fallback = S.errors.queueRemoveFailed)
    }
}
