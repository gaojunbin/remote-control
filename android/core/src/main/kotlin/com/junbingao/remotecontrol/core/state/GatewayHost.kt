package com.junbingao.remotecontrol.core.state

import java.net.URI
import java.net.URISyntaxException

/**
 * The gateway as the Settings header prints it: the host, with the port where there is one, and no
 * scheme.
 *
 * The scheme is the app's business and not the reader's — every gateway is https but a development
 * one — so the line under the username says `rc.example.com` and the header has room for the name
 * beside it.
 */
object GatewayHost {
    fun of(origin: String): String {
        val parts = try {
            URI(origin)
        } catch (_: URISyntaxException) {
            null
        }
        val host = parts?.host ?: return stripScheme(origin)
        if (parts.port < 0) return host
        return "$host:${parts.port}"
    }

    /** What is left of an origin no URI reader could read. */
    private fun stripScheme(origin: String): String {
        var text = origin
        val separator = text.indexOf("://")
        if (separator >= 0) text = text.substring(separator + 3)
        return text.trimEnd('/')
    }
}
