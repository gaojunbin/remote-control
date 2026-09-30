package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.protocol.AppsInfo
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.PolishInfo
import com.junbingao.remotecontrol.core.protocol.ProtocolFailure
import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.STTConfig
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.UserIdentity
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.get
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

@Serializable
data class LoginResponse(val token: String, val exp: Long = 0, val user: UserIdentity = UserIdentity(username = ""))

@Serializable
data class SessionInfoResponse(val user: UserIdentity = UserIdentity(username = ""), val exp: Long = 0)

@Serializable
data class PushConfig(
    @SerialName("web_enabled") val webEnabled: Boolean = false,
    @SerialName("apns_enabled") val apnsEnabled: Boolean = false,
) {
    companion object {
        val disabled = PushConfig(webEnabled = false, apnsEnabled = false)
    }
}

/**
 * Amendment A22: the client wheel this gateway serves. A gateway running from a developer checkout
 * has no wheel on disk and omits the whole object, which is why every field of it is known
 * together or not at all.
 */
@Serializable
data class ClientBuild(val version: String = "", val build: String = "", val url: String = "")

@Serializable
data class GatewayConfig(
    @SerialName("public_origin") val publicOrigin: String = "",
    val stt: STTConfig = STTConfig.disabled,
    /** Amendment A29. Absent on an older gateway, which means disabled. */
    val polish: PolishInfo = PolishInfo.disabled,
    /** Amendment A31. Absent on an older gateway, which means no minimum. */
    val apps: AppsInfo? = null,
    val push: PushConfig = PushConfig.disabled,
    val version: String = "",
    val client: ClientBuild? = null,
) {
    /** The build every device is measured against, or null when this gateway serves none and no device can be said to be out of date. */
    val servedBuild: String? get() = client?.build?.takeIf { it.isNotEmpty() }

    /**
     * What an update would install, in the words a person reads — the version of the wheel this
     * gateway serves. Null when the gateway serves none, and on an older gateway whose config
     * carries no version, which is why the screens that name it have wording for not knowing.
     */
    val servedVersion: String? get() = client?.version?.takeIf { it.isNotEmpty() }

    companion object {
        val empty = GatewayConfig(publicOrigin = "", stt = STTConfig.disabled, push = PushConfig.disabled, version = "")
    }
}

@Serializable
data class DeviceListResponse(val devices: List<Device>)

@Serializable
data class DeviceResponse(val device: Device)

@Serializable
data class SessionListResponse(val sessions: List<Session>)

@Serializable
data class InstallCommands(val macos: String = "", val linux: String = "") {
    /**
     * The one command to run on the host. The gateway hands out a key per platform so a platform
     * whose command really differs can be added without a wire change, but the two are the same
     * string today — the installer tells macOS from Linux itself (`uname`) — so the apps read one
     * of them and ask nobody to choose (`docs/DESIGN.md` § "Add device").
     */
    val command: String get() = macos
}

/** A single-use pairing grant with a ten-minute lifetime. */
@Serializable
data class PairingGrant(val code: String, @SerialName("expires_at") val expiresAt: Long, val install: InstallCommands)

/**
 * Amendment A23: what claiming a host's request hands back — the ordinary pairing code, minted
 * for the caller, which the host is already waiting for. There is no install command with it: the
 * host ran one to get here.
 */
@Serializable
data class PairingClaim(val code: String, @SerialName("expires_at") val expiresAt: Long)

@Serializable
data class APNSRegistration(
    val token: String,
    val environment: String,
    @SerialName("bundle_id") val bundleID: String,
) {
    companion object {
        /** Lowercase hex, as APNs hands it over, and never longer than a token can be. */
        fun tokenHex(data: ByteArray): String {
            if (data.isEmpty() || data.size > 512) throw TransportError.InvalidResponse
            return data.joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }
}

/** The `rc` object carried inside a push payload. It never contains prompt text, tool output or file contents. */
@Serializable(with = PushRoute.Serializer::class)
data class PushRoute(
    val version: Int = 1,
    val kind: PushKind,
    val deviceID: String,
    val sessionID: String,
    val deviceName: String,
    val title: String,
) {
    /** `remotecontrol://session?device=<id>&id=<session_id>` */
    val deepLink: URI? get() = SessionLink(deviceID = deviceID, sessionID = sessionID).url

    /** The wire's own spelling, where an absent version reads as none rather than as this build's. */
    @Serializable
    private class Wire(
        @SerialName("v") val version: Int = 0,
        val kind: PushKind = PushKind.error,
        @SerialName("device_id") val deviceID: String,
        @SerialName("session_id") val sessionID: String,
        @SerialName("device_name") val deviceName: String = "",
        val title: String = "",
    )

    object Serializer : KSerializer<PushRoute> {
        private val wire = Wire.serializer()
        override val descriptor = wire.descriptor

        override fun deserialize(decoder: Decoder): PushRoute {
            val route = decoder.decodeSerializableValue(wire)
            return PushRoute(version = route.version, kind = route.kind, deviceID = route.deviceID,
                             sessionID = route.sessionID, deviceName = route.deviceName, title = route.title)
        }

        override fun serialize(encoder: Encoder, value: PushRoute) = encoder.encodeSerializableValue(
            wire, Wire(value.version, value.kind, value.deviceID, value.sessionID, value.deviceName, value.title))
    }

    companion object {
        /** Parse a push payload that was serialized before it crossed a thread. A payload larger than 4 KiB is rejected outright. */
        operator fun invoke(userInfo: ByteArray): PushRoute {
            if (userInfo.size > 4096) throw TransportError.ResponseTooLarge
            val json = JSONValue.parse(userInfo)
            val route = json["rc"] ?: throw ProtocolFailure.Malformed("push payload has no rc object")
            val decoded = route.decode<PushRoute>()
            if (decoded.version != 1) throw ProtocolFailure.UnsupportedVersion(decoded.version)
            return decoded
        }
    }
}

/** The app's own deep link into one session. */
data class SessionLink(val deviceID: String, val sessionID: String) {
    val url: URI?
        get() = runCatching { URI("$scheme://session?device=${query(deviceID)}&id=${query(sessionID)}") }.getOrNull()

    companion object {
        const val scheme = "remotecontrol"

        /** The session a link names, or null for anything that is not one of the app's own links. */
        operator fun invoke(url: URI): SessionLink? {
            if (url.scheme?.lowercase() != scheme || url.host != "session") return null
            val items = url.rawQuery?.split("&")?.map { item ->
                val name = item.substringBefore("=")
                val value = if ('=' in item) item.substringAfter("=") else null
                decode(name) to value?.let(::decode)
            } ?: return null
            val device = items.firstOrNull { it.first == "device" }?.second
            val session = items.firstOrNull { it.first == "id" }?.second
            if (device.isNullOrEmpty() || session.isNullOrEmpty()) return null
            return SessionLink(deviceID = device, sessionID = session)
        }

        /** A query value as `URLComponents.queryItems` writes it: `&`, `=` and space escaped, `+` left alone. */
        private fun query(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%2B", "+")

        /** A query item's percent-encoding undone; a `+` stays a plus, as `URLComponents` reads it. */
        private fun decode(value: String): String? =
            runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrNull()
    }
}
