package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.state.L10n
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Amendment A29: how hard the gateway's model is allowed to work on a dictation. Both strengths
 * keep the language the words were spoken in and return text only; neither adds a request the
 * speaker did not make.
 */
@Serializable
enum class PolishStrength(val rawValue: String) {
    /** Fillers, false starts, repetitions, plain mishearings and punctuation. The speaker's words and their order are kept. */
    @SerialName("moderate") moderate("moderate"),

    /** Also restructures for clarity and precision, and resolves a vague reference from what the conversation already said. */
    @SerialName("strong") strong("strong");

    val title: String
        get() = when (this) {
            moderate -> L10n.string("Moderate")
            strong -> L10n.string("Strong")
        }

    companion object {
        val allCases: List<PolishStrength> get() = entries

        operator fun invoke(rawValue: String): PolishStrength? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** One model the gateway's configured provider offers. */
@Serializable
data class PolishModel(
    val id: String = "",
    /** A provider that names a model and nothing else is still a model. */
    val label: String = id,
)

/** `GET /api/polish/models`. */
@Serializable(with = PolishModelsResponse.Serializer::class)
data class PolishModelsResponse(val models: List<PolishModel>) {
    @Serializable
    private class Wire(val models: List<PolishModel> = emptyList())

    object Serializer : KSerializer<PolishModelsResponse> {
        private val wire = Wire.serializer()
        override val descriptor = wire.descriptor

        override fun deserialize(decoder: Decoder): PolishModelsResponse =
            PolishModelsResponse(decoder.decodeSerializableValue(wire).models.filter { it.id.isNotEmpty() })

        override fun serialize(encoder: Encoder, value: PolishModelsResponse) =
            encoder.encodeSerializableValue(wire, Wire(value.models))
    }
}

/** Who said one of the messages the model is given as conversation. */
@Serializable
enum class PolishRole(val rawValue: String) {
    @SerialName("user") user("user"),
    @SerialName("assistant") assistant("assistant");

    companion object {
        operator fun invoke(rawValue: String): PolishRole? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** One message of the recent conversation, as the app already shows it. */
@Serializable
data class PolishContextItem(val role: PolishRole, val text: String)

/** `POST /api/polish`: the dictated words, what the user chose, and the conversation they were spoken into. */
@Serializable
data class PolishRequest(
    val text: String,
    val model: String,
    val strength: PolishStrength,
    /** The dictation language the user chose, or nothing when it is automatic. */
    val language: String? = null,
    @Required val context: List<PolishContextItem> = emptyList(),
)

/** The polished text and nothing else. */
@Serializable
data class PolishResponse(val text: String = "")
