package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.state.L10n
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

@JvmInline
@Serializable(with = GatewayErrorCode.Serializer::class)
value class GatewayErrorCode(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val badRequest = GatewayErrorCode("bad_request")
        val unauthorized = GatewayErrorCode("unauthorized")
        val forbidden = GatewayErrorCode("forbidden")
        val notFound = GatewayErrorCode("not_found")
        val deviceOffline = GatewayErrorCode("device_offline")
        val agentUnavailable = GatewayErrorCode("agent_unavailable")
        val conflict = GatewayErrorCode("conflict")
        val timeout = GatewayErrorCode("timeout")
        val internalError = GatewayErrorCode("internal")
        val unsupported = GatewayErrorCode("unsupported")
        val tooLarge = GatewayErrorCode("too_large")
    }

    object Serializer : WireEnumSerializer<GatewayErrorCode>("GatewayErrorCode", ::GatewayErrorCode)
}

/** The error object carried by an unsuccessful `reply`, and what a request it answers throws. */
@Serializable(with = GatewayErrorBody.Serializer::class)
data class GatewayErrorBody(val code: GatewayErrorCode, override val message: String) :
    Exception(message, null, false, false) {

    @Serializable
    private class Wire(
        val code: GatewayErrorCode = GatewayErrorCode.internalError,
        val message: String? = null,
    )

    object Serializer : KSerializer<GatewayErrorBody> {
        private val wire = Wire.serializer()
        override val descriptor = wire.descriptor
        override fun deserialize(decoder: Decoder): GatewayErrorBody {
            val body = decoder.decodeSerializableValue(wire)
            return GatewayErrorBody(body.code, body.message ?: body.code.rawValue)
        }

        override fun serialize(encoder: Encoder, value: GatewayErrorBody) =
            encoder.encodeSerializableValue(wire, Wire(value.code, value.message))
    }
}

@Serializable
data class STTConfig(
    val enabled: Boolean = false,
    /**
     * `["auto"]` since A44: the gateway's provider detects the language, so there is nothing here
     * for the app to offer. The phone's own recogniser has a list of its own (`DictationLanguage`).
     */
    val languages: List<String> = emptyList(),
) {
    companion object {
        val disabled = STTConfig(enabled = false, languages = emptyList())
    }
}

/**
 * Amendment A29: whether this gateway can polish a dictation through a model its operator
 * configured. A gateway older than the amendment sends nothing, which means it cannot.
 */
@Serializable
data class PolishInfo(val enabled: Boolean = false) {
    companion object {
        val disabled = PolishInfo(enabled = false)
    }
}

/** The first frame the gateway sends on `/ws/app`. */
@Serializable
data class HelloFrame(
    @SerialName("protocol") val protocolVersion: Int = 0,
    @SerialName("gateway_version") val gatewayVersion: String = "",
    val user: UserIdentity = UserIdentity(username = ""),
    val devices: List<Device> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val stt: STTConfig = STTConfig.disabled,
    /** Amendment A29. Absent on an older gateway, which means disabled. */
    val polish: PolishInfo = PolishInfo.disabled,
    /** Amendment A31. Absent on an older gateway, which means no minimum. */
    val apps: AppsInfo? = null,
    /**
     * Amendment A35: the account's preferences. Absent on an older gateway, which means the
     * switches this object holds are not offered at all.
     */
    val preferences: Preferences? = null,
    @SerialName("server_time") val serverTime: Long = 0,
)

@JvmInline
@Serializable(with = PairingStep.Serializer::class)
value class PairingStep(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    /** Ordinal used to light up the checklist in the "Add device" sheet. */
    val order: Int
        get() = when (this) {
            waiting -> 0
            enrolled -> 1
            online -> 2
            agents -> 3
            else -> 0
        }

    companion object {
        val waiting = PairingStep("waiting")
        val enrolled = PairingStep("enrolled")
        val online = PairingStep("online")
        val agents = PairingStep("agents")
    }

    object Serializer : WireEnumSerializer<PairingStep>("PairingStep", ::PairingStep)
}

@Serializable
data class PairingProgress(val code: String, val step: PairingStep, val device: Device? = null)

/** A frame arriving on `/ws/app`. */
sealed interface AppFrame {
    data class Hello(val hello: HelloFrame) : AppFrame
    data class DeviceUpdated(val device: Device) : AppFrame
    data class DeviceRemoved(val deviceID: String) : AppFrame
    data class SessionUpdated(val session: Session) : AppFrame
    data class SessionRemoved(val sessionID: String, val deviceID: String?) : AppFrame
    data class SessionEvent(
        val sessionID: String,
        val deviceID: String?,
        val event: com.junbingao.remotecontrol.core.protocol.SessionEvent,
    ) : AppFrame

    data class PairingProgress(val progress: com.junbingao.remotecontrol.core.protocol.PairingProgress) : AppFrame

    /** Amendment A35: the account's preferences changed, here or in another app. */
    data class PreferencesUpdated(val preferences: Preferences) : AppFrame

    /**
     * Amendment A38: bytes a terminal on a device produced, for the one connection holding it —
     * which is this one, or the gateway would not have sent them.
     */
    data class TerminalOutput(val output: com.junbingao.remotecontrol.core.protocol.TerminalOutput) : AppFrame

    /** Amendment A38: that terminal's shell ended. */
    data class TerminalExited(val exited: com.junbingao.remotecontrol.core.protocol.TerminalExited) : AppFrame
    data object Ping : AppFrame
    data class Reply(val id: String, val result: Result<JsonElement>) : AppFrame
    data class Unknown(val type: String, val raw: JsonElement) : AppFrame

    companion object {
        operator fun invoke(json: JsonElement): AppFrame {
            val frame = json.objectValue
            val type = frame?.string("type") ?: throw ProtocolFailure.Malformed("frame has no type")
            return when (type) {
                "hello" -> Hello(json.decode())
                "device.updated" -> DeviceUpdated(
                    (frame["device"] ?: throw ProtocolFailure.Malformed("device.updated")).decode())
                "device.removed" -> DeviceRemoved(
                    frame.string("device_id") ?: throw ProtocolFailure.Malformed("device.removed"))
                "session.updated" -> SessionUpdated(
                    (frame["session"] ?: throw ProtocolFailure.Malformed("session.updated")).decode())
                "session.removed" -> SessionRemoved(
                    sessionID = frame.string("session_id") ?: throw ProtocolFailure.Malformed("session.removed"),
                    deviceID = frame.string("device_id"))
                "session.event" -> {
                    val sessionID = frame.string("session_id")
                    val event = frame["event"]
                    if (sessionID == null || event == null) throw ProtocolFailure.Malformed("session.event")
                    SessionEvent(sessionID = sessionID, deviceID = frame.string("device_id"), event = event.decode())
                }
                "pairing.progress" -> PairingProgress(json.decode())
                "preferences.updated" -> PreferencesUpdated(
                    (frame["preferences"] ?: throw ProtocolFailure.Malformed("preferences.updated")).decode())
                "terminal.output" -> TerminalOutput(com.junbingao.remotecontrol.core.protocol.TerminalOutput(json = json))
                "terminal.exited" -> TerminalExited(com.junbingao.remotecontrol.core.protocol.TerminalExited(json = json))
                "ping" -> Ping
                "reply" -> {
                    val id = frame.string("id") ?: throw ProtocolFailure.Malformed("reply")
                    if (frame.bool("ok") == true) {
                        Reply(id, Result.success(frame["result"] ?: JSONValue.emptyObject))
                    } else {
                        Reply(id, Result.failure((frame["error"] ?: JSONValue.emptyObject).decode<GatewayErrorBody>()))
                    }
                }
                else -> Unknown(type = type, raw = json)
            }
        }

        operator fun invoke(data: ByteArray): AppFrame = AppFrame(json = JSONValue.parse(data))
    }
}

sealed class ProtocolFailure : Exception(null, null, false, false) {
    data class Malformed(val detail: String) : ProtocolFailure()
    data class UnsupportedVersion(val version: Int) : ProtocolFailure()

    val errorDescription: String
        get() = when (this) {
            is Malformed -> L10n.string("The gateway sent a frame this app could not read (%@).", detail)
            is UnsupportedVersion -> L10n.string(
                "This app speaks protocol %lld; the gateway speaks %lld. Update both sides.",
                RemoteProtocol.version, version)
        }

    override val message: String get() = errorDescription
}
