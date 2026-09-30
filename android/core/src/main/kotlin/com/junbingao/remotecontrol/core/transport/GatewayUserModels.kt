package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.protocol.AppsInfo
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.core.protocol.boolValue
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.get
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `GET /api/health`, the one route an app reads before it has an account.
 *
 * `registrationOpen` is the only reason the sign-in form calls it: it decides whether "Create an
 * account" is offered at all (protocol 3.1, A24).
 */
@Serializable(with = HealthResponse.Serializer::class)
data class HealthResponse(
    val version: String,
    val protocolVersion: Int,
    val registrationOpen: Boolean,
    /**
     * Amendment A31: the oldest app build this gateway works with. This route is the one that
     * answers before anyone has a credential, so an app too old for the gateway is stopped at the
     * sign-in form.
     */
    val apps: AppsInfo? = null,
) {
    @Serializable
    private class Fields(
        val version: String = "",
        @SerialName("protocol") val protocolVersion: Int = 0,
        val apps: AppsInfo? = null,
    )

    object Serializer : KSerializer<HealthResponse> {
        private val fields = Fields.serializer()
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("HealthResponse")

        // `auth` is read as loosely as RCCore reads it: anything but an object with a boolean in
        // it is registration closed.
        override fun deserialize(decoder: Decoder): HealthResponse {
            val input = decoder as? JsonDecoder ?: throw SerializationException("HealthResponse reads JSON only")
            val element = input.decodeJsonElement()
            val read = element.decode(fields)
            return HealthResponse(version = read.version, protocolVersion = read.protocolVersion,
                                  registrationOpen = element["auth"]?.get("registration_open")?.boolValue ?: false,
                                  apps = read.apps)
        }

        override fun serialize(encoder: Encoder, value: HealthResponse) {
            val output = encoder as? JsonEncoder ?: throw SerializationException("HealthResponse writes JSON only")
            val members = LinkedHashMap<String, JsonElement>()
            members["version"] = JsonPrimitive(value.version)
            members["protocol"] = JsonPrimitive(value.protocolVersion)
            value.apps?.let { members["apps"] = output.json.encodeToJsonElement(AppsInfo.serializer(), it) }
            members["auth"] = JsonObject(mapOf("registration_open" to JsonPrimitive(value.registrationOpen)))
            output.encodeJsonElement(JsonObject(members))
        }
    }
}

/**
 * `GET /api/users` (protocol 3.9). The switch at the top of the screen and the rows under it arrive
 * together, so the page never draws one without the other.
 */
@Serializable
data class UserListResponse(
    val users: List<UserRecord> = emptyList(),
    @SerialName("registration_open") val registrationOpen: Boolean = false,
)

/** `POST /api/users` and `PATCH /api/users/{username}` (protocol 3.9). */
@Serializable
data class UserResponse(val user: UserRecord)

/** `PATCH /api/registration` (protocol 3.9). */
@Serializable
data class RegistrationResponse(val open: Boolean = false)
