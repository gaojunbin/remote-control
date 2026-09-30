package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.state.L10n
import com.junbingao.remotecontrol.core.swiftInt
import com.junbingao.remotecontrol.core.trimmingWhitespacesAndNewlines
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.IDN
import java.net.URI
import java.net.URLDecoder

sealed class TransportError : Exception(null, null, false, false) {
    data object InvalidEndpoint : TransportError()
    data object InvalidResponse : TransportError()
    data object ResponseTooLarge : TransportError()
    data object Unauthorized : TransportError()
    data class Http(val status: Int, val code: String?) : TransportError()
    data object NotConnected : TransportError()
    data object DeliveryUncertain : TransportError()
    data object RequestTimedOut : TransportError()
    data class ProtocolMismatch(val version: Int) : TransportError()
    data object SecureStorageUnavailable : TransportError()

    val errorDescription: String
        get() = when (this) {
            InvalidEndpoint -> L10n.string("Enter the full gateway address, for example https://rc.example.com.")
            InvalidResponse -> L10n.string("The gateway sent a response this app could not read.")
            ResponseTooLarge -> L10n.string("That response was too large to load.")
            Unauthorized -> L10n.string("Your session expired. Sign in again.")
            is Http -> when (status) {
                403 -> L10n.string("The gateway refused this request.")
                404 -> L10n.string("That device or session no longer exists.")
                429 -> L10n.string("Too many attempts. Wait a moment and try again.")
                503 -> L10n.string("The gateway does not have this feature enabled.")
                else -> L10n.string("The gateway request failed (%lld).", status)
            }
            NotConnected -> L10n.string("Not connected to the gateway.")
            DeliveryUncertain -> L10n.string("Delivery unconfirmed. Check the session before sending again.")
            RequestTimedOut -> L10n.string("The gateway did not answer in time.")
            is ProtocolMismatch -> L10n.string(
                "This app speaks protocol %lld; the gateway speaks %lld. Update both sides.",
                RemoteProtocol.version, version)
            SecureStorageUnavailable -> L10n.string("Could not reach the keychain. Unlock this device and try again.")
        }

    override val message: String get() = errorDescription
}

/**
 * A canonical gateway origin: scheme, host and port only.
 *
 * https is required, because a bearer token travels on every request. Plain http is accepted only
 * for loopback and private-network hosts, so a developer can point the app at a gateway running on
 * their own machine.
 */
@Serializable(with = GatewayEndpoint.Serializer::class)
class GatewayEndpoint private constructor(val origin: String, val isSecure: Boolean) {
    val url: URI get() = runCatching { URI(origin) }.getOrElse { URI("file:///") }

    /** `wss://host/ws/app`, or `ws://` for a development origin. */
    fun socketURL(path: String): URI {
        val scheme = if (isSecure) "wss" else "ws"
        return runCatching { URI(scheme + origin.substring(origin.indexOf(':')) + path) }.getOrElse { url }
    }

    fun apiURL(path: String): URI =
        runCatching { URI(origin + "/" + path.removePrefix("/")) }.getOrElse { url }

    override fun equals(other: Any?): Boolean =
        other is GatewayEndpoint && origin == other.origin && isSecure == other.isSecure

    override fun hashCode(): Int = 31 * origin.hashCode() + isSecure.hashCode()

    override fun toString(): String = origin

    companion object {
        /** A never-reachable origin, so nothing needs a force unwrap to hold a `GatewayEndpoint`. */
        val placeholder = GatewayEndpoint(origin = "https://invalid.invalid", isSecure = true)

        /** The address a person typed, as the origin it names. Throws [TransportError.InvalidEndpoint] for anything else. */
        operator fun invoke(text: String): GatewayEndpoint {
            val parts = OriginParts.parse(text) ?: throw TransportError.InvalidEndpoint
            val isSecure = when (parts.scheme) {
                "https" -> true
                "http" -> if (isDevelopmentHost(parts.host)) false else throw TransportError.InvalidEndpoint
                else -> throw TransportError.InvalidEndpoint
            }
            val port = parts.port?.takeUnless { (isSecure && it == 443) || (!isSecure && it == 80) }
            val scheme = if (isSecure) "https" else "http"
            return GatewayEndpoint(origin = "$scheme://${parts.asciiHost}" + (port?.let { ":$it" } ?: ""),
                                   isSecure = isSecure)
        }

        /** Loopback and RFC 1918 addresses, plus `.local` names from Bonjour. */
        fun isDevelopmentHost(host: String): Boolean {
            if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]") return true
            if (host.endsWith(".local") || host.endsWith(".localhost")) return true
            val parts = host.split(".").filter { it.isNotEmpty() }.mapNotNull { it.swiftInt() }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            if (parts[0] == 10 || parts[0] == 127) return true
            if (parts[0] == 192 && parts[1] == 168) return true
            if (parts[0] == 172 && parts[1] in 16..31) return true
            return false
        }
    }

    object Serializer : KSerializer<GatewayEndpoint> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("GatewayEndpoint", PrimitiveKind.STRING)
        override fun deserialize(decoder: Decoder): GatewayEndpoint = GatewayEndpoint(decoder.decodeString())
        override fun serialize(encoder: Encoder, value: GatewayEndpoint) = encoder.encodeString(value.origin)
    }
}

/**
 * What `URLComponents(string:)` makes of an address, for the few parts an origin may have: a
 * scheme, a host and a port, with no user, path beyond `/`, query or fragment. The host comes back
 * decoded and lower-cased, which is what the development check reads, and as the ASCII the origin
 * is written in.
 */
private class OriginParts(val scheme: String, val host: String, val asciiHost: String, val port: Int?) {
    companion object {
        private val schemePattern = Regex("[A-Za-z][A-Za-z0-9+.-]*")
        private const val UNRESERVED_EXTRA = "-._~"
        private const val SUB_DELIMS = "!$&'()*+,;="

        fun parse(text: String): OriginParts? {
            val trimmed = text.trimmingWhitespacesAndNewlines()
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val colon = withScheme.indexOf(':')
            val scheme = withScheme.substring(0, colon)
            if (!schemePattern.matches(scheme)) return null
            val rest = withScheme.substring(colon + 1)
            if (!rest.startsWith("//")) return null
            val afterSlashes = rest.substring(2)
            val authorityEnd = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it < 0) afterSlashes.length else it }
            val authority = afterSlashes.substring(0, authorityEnd)
            val remainder = afterSlashes.substring(authorityEnd)
            // A query, a fragment or a user of any kind — even an empty one — is not an origin.
            if ('?' in remainder || '#' in remainder || '@' in authority) return null
            if (remainder.isNotEmpty() && remainder != "/") return null
            val (hostText, portText) = splitHostAndPort(authority) ?: return null
            val port = when {
                portText.isNullOrEmpty() -> null
                portText.all { it in '0'..'9' } -> portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                else -> return null
            }
            val (host, asciiHost) = readHost(hostText) ?: return null
            return OriginParts(scheme.lowercase(), host, asciiHost, port)
        }

        private fun splitHostAndPort(authority: String): Pair<String, String?>? {
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                val after = authority.substring(close + 1)
                return when {
                    after.isEmpty() -> authority to null
                    after.startsWith(":") -> authority.substring(0, close + 1) to after.substring(1)
                    else -> null
                }
            }
            val colon = authority.indexOf(':')
            return if (colon < 0) authority to null else authority.substring(0, colon) to authority.substring(colon + 1)
        }

        /** The decoded, lower-cased host and its ASCII spelling, or null when it is not a host at all. */
        private fun readHost(text: String): Pair<String, String>? {
            if (text.isEmpty()) return null
            if (text.startsWith("[")) {
                val literal = text.lowercase()
                if (literal.drop(1).dropLast(1).any { !(it.isLetterOrDigit() && it.code < 0x80) && it !in ":.%_~-" }) return null
                return literal to literal
            }
            for (character in text) {
                val allowed = character.code >= 0x80 || character.isLetterOrDigit() ||
                    character in UNRESERVED_EXTRA || character in SUB_DELIMS || character == '%'
                if (!allowed) return null
            }
            val decoded = try {
                URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                return null
            }.lowercase()
            if (decoded.isEmpty()) return null
            for (character in decoded) {
                if (character.code >= 0x80) continue
                if (!(character.isLetterOrDigit() || character in UNRESERVED_EXTRA || character in SUB_DELIMS)) return null
            }
            if (decoded.all { it.code < 0x80 }) return decoded to decoded
            val ascii = try {
                IDN.toASCII(decoded, IDN.ALLOW_UNASSIGNED).lowercase()
            } catch (_: IllegalArgumentException) {
                return null
            }
            return decoded to ascii
        }
    }
}
