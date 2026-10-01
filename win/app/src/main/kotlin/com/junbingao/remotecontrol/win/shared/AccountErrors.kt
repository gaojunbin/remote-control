package com.junbingao.remotecontrol.win.shared

import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.strings.S

/**
 * `web/src/lib/accountErrors.ts` — A24: what the account routes of 3.9 refuse, in words, read from
 * `error.code` so the sentence follows the gateway rather than the status line alone.
 */
object AccountErrors {
    /**
     * `conflict` means two different things on these routes — a taken username on
     * `POST /api/users`, and `admin` refusing to be changed elsewhere — so the caller supplies the
     * sentence for its own route.
     */
    fun userErrorText(error: Throwable, conflict: String): String {
        val code = (error as? TransportError.Http)?.code ?: return S.errors.generic
        return when (code) {
            GatewayErrorCode.conflict.rawValue -> conflict
            GatewayErrorCode.badRequest.rawValue -> S.account.rules
            GatewayErrorCode.forbidden.rawValue -> S.account.notAllowed
            GatewayErrorCode.notFound.rawValue -> S.account.gone
            else -> S.errors.generic
        }
    }
}
