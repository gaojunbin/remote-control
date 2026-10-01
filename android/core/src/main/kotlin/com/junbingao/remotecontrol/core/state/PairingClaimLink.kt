package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import java.net.URI
import java.net.URISyntaxException

/**
 * Amendment A23: the link a host prints as a QR code, `<origin>/pair#<token>`.
 *
 * The origin is part of the payload and is checked against the gateway this app is signed in to. A
 * code for someone else's gateway is not an error to recover from: it simply is not ours, and the
 * scanner says so and keeps looking.
 */
@ConsistentCopyVisibility
data class PairingClaimLink private constructor(val token: String) {
    companion object {
        /** A claim token is Crockford base32. Anything else was not printed by a client of ours and is never sent to the gateway. */
        private const val ALLOWED = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /**
         * Parse a scanned payload, or return null when it is not this gateway's pairing link. The
         * comparison is against the canonical origin, so a trailing slash, a default port or a
         * capitalised host still matches.
         */
        operator fun invoke(payload: String, gateway: GatewayEndpoint): PairingClaimLink? {
            val parts = try {
                URI(payload.trimmed)
            } catch (_: URISyntaxException) {
                return null
            }
            val fragment = parts.fragment
            val scheme = parts.scheme
            val host = parts.host
            if (fragment.isNullOrEmpty() || (parts.path != "/pair" && parts.path != "/pair/") ||
                scheme == null || host == null) return null
            val canonicalScheme = scheme.lowercase()
            val port = parts.port.takeIf { it >= 0 }
                ?.takeUnless { (canonicalScheme == "https" && it == 443) || (canonicalScheme == "http" && it == 80) }
            val origin = "$canonicalScheme://${host.lowercase()}" + (port?.let { ":$it" } ?: "")
            if (origin != gateway.origin) return null
            val candidate = fragment.uppercase()
            if (candidate.length !in 8..64 || !candidate.all { it in ALLOWED }) return null
            return PairingClaimLink(token = candidate)
        }

        /**
         * The same test against every origin this app knows the gateway by. A person who signed in
         * on the LAN address reads a QR code that carries the gateway's public one, and both are
         * this gateway.
         */
        operator fun invoke(payload: String, gateways: List<GatewayEndpoint>): PairingClaimLink? =
            gateways.asSequence().mapNotNull { PairingClaimLink(payload = payload, gateway = it) }.firstOrNull()
    }
}
