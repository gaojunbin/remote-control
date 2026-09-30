package com.junbingao.remotecontrol.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Attachment metadata carried by a `user_message` event. The bytes themselves only travel
 * outbound, in `session.send`.
 */
@Serializable
data class AttachmentInfo(val name: String, val mime: String, val size: Int)

@Serializable
data class UserMessagePayload(
    val text: String = "",
    val attachments: List<AttachmentInfo> = emptyList(),
    val source: EventSource = EventSource.remote,
    /**
     * Amendment A10: set only on `shared` sessions. Absent means the message was an ordinary
     * prompt that reached the agent directly.
     */
    val delivery: MessageDelivery? = null,
)

/**
 * `assistant_text` and `thinking` share the streaming shape: a `delta` appends to the block, a
 * `done: true` event carries the full `text`.
 */
@Serializable
data class StreamTextPayload(
    val delta: String? = null,
    val text: String? = null,
    val done: Boolean = false,
    @SerialName("duration_ms") val durationMS: Int? = null,
)

@Serializable
data class DiffPayload(
    val path: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String? = null,
    @SerialName("patch_truncated") val patchTruncated: Boolean = false,
)

@Serializable
data class ToolCallPayload(
    val tool: String = "",
    @SerialName("tool_kind") val kind: ToolKind = ToolKind.derived(fromTool = tool),
    val title: String = "",
    val status: ToolStatus = ToolStatus.running,
    val input: JsonElement? = null,
    @SerialName("input_truncated") val inputTruncated: Boolean = false,
    val output: String? = null,
    @SerialName("output_truncated") val outputTruncated: Boolean = false,
    val summary: String? = null,
    val diff: DiffPayload? = null,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("ended_at") val endedAt: Long? = null,
    @SerialName("duration_ms") val durationMS: Int? = null,
) {
    /** True when the device withheld part of the payload; the full block is fetched on demand with `session.block`. */
    val isTruncated: Boolean get() = inputTruncated || outputTruncated || diff?.patchTruncated == true
}

@Serializable
data class TodoItem(val id: String, val text: String, val status: TodoStatus)

@Serializable
data class TodosPayload(val items: List<TodoItem> = emptyList()) {
    val counts: TodoCounts
        get() = TodoCounts(total = items.size, done = items.count { it.status == TodoStatus.completed })
}

/**
 * An option on an approval card. The id is agent-defined and opaque; the UI renders exactly the
 * options it was given and never invents one.
 */
@Serializable
data class ApprovalOption(
    val id: String,
    val label: String = id,
    val style: OptionStyle = OptionStyle.secondary,
)

@Serializable
data class ApprovalDecision(@SerialName("option_id") val optionID: String, val by: EventSource)

@Serializable
data class ApprovalPayload(
    @SerialName("request_id") val requestID: String,
    val tool: String = "",
    @SerialName("tool_kind") val kind: ToolKind = ToolKind.derived(fromTool = tool),
    val title: String = "",
    val input: JsonElement? = null,
    val diff: DiffPayload? = null,
    val options: List<ApprovalOption> = emptyList(),
    val status: RequestStatus = RequestStatus.pending,
    val decision: ApprovalDecision? = null,
) {
    /**
     * What to call the option a resolved request settled on, or null when there is nothing to
     * name because it was answered elsewhere. An id this block never offered still renders
     * verbatim rather than leaving the card blank.
     */
    val resolvedOptionLabel: String?
        get() {
            val decision = decision ?: return null
            if (decision.optionID == elsewhereOptionID) return null
            return options.firstOrNull { it.id == decision.optionID }?.label ?: decision.optionID
        }

    /**
     * Every approval carries at least one primary and one danger option, so the UI can place
     * accept and reject consistently without knowing the ids.
     */
    val primaryOption: ApprovalOption? get() = options.firstOrNull { it.style == OptionStyle.primary }
    val dangerOption: ApprovalOption? get() = options.firstOrNull { it.style == OptionStyle.danger }
    val otherOptions: List<ApprovalOption>
        get() = options.filter { it.id != primaryOption?.id && it.id != dangerOption?.id }

    companion object {
        /**
         * Amendment A11: the reserved id a device uses when a shared request was resolved by
         * whoever else holds the session. It is never an option and is never sent back, so
         * nothing looks it up in `options`.
         */
        const val elsewhereOptionID = "elsewhere"
    }
}

@Serializable
data class QuestionOption(val id: String, val label: String = id, val description: String? = null)

@Serializable
data class QuestionItem(
    val id: String,
    val prompt: String = "",
    val options: List<QuestionOption> = emptyList(),
    val multi: Boolean = false,
    @SerialName("allow_text") val allowText: Boolean = false,
    val secret: Boolean = false,
)

/** One question's answer: either chosen option ids, or free text. */
@Serializable(with = QuestionAnswer.Serializer::class)
sealed interface QuestionAnswer {
    data class Options(val ids: List<String>) : QuestionAnswer
    data class Text(val value: String) : QuestionAnswer

    object Serializer : KSerializer<QuestionAnswer> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("QuestionAnswer")

        override fun deserialize(decoder: Decoder): QuestionAnswer {
            val element = (decoder as? JsonDecoder ?: throw SerializationException("QuestionAnswer reads JSON only"))
                .decodeJsonElement()
            val ids = (element as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
            if (ids != null && ids.all { it != null }) return Options(ids.filterNotNull())
            val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw SerializationException("an answer is a list of option ids or a string")
            return Text(text)
        }

        override fun serialize(encoder: Encoder, value: QuestionAnswer) {
            val output = encoder as? JsonEncoder ?: throw SerializationException("QuestionAnswer writes JSON only")
            when (value) {
                is Options -> output.encodeSerializableValue(ListSerializer(String.serializer()), value.ids)
                is Text -> output.encodeString(value.value)
            }
        }
    }
}

@Serializable
data class QuestionPayload(
    @SerialName("request_id") val requestID: String,
    val questions: List<QuestionItem> = emptyList(),
    val status: RequestStatus = RequestStatus.pending,
    val answers: Map<String, QuestionAnswer>? = null,
    /**
     * Amendment A20: on a resolved question, who answered it. An attached Claude Code session shows
     * its own dialog beside this card and whichever is answered first wins, so the other side has
     * to be told which that was.
     */
    val by: EventSource? = null,
)

@Serializable
data class TurnStartedPayload(
    @SerialName("turn_id") val turnID: String = "",
    val trigger: EventSource = EventSource.remote,
)

@Serializable
data class TurnCompletedPayload(
    @SerialName("turn_id") val turnID: String = "",
    @SerialName("stop_reason") val stopReason: StopReason = StopReason.completed,
    @SerialName("duration_ms") val durationMS: Int = 0,
    val usage: SessionUsage? = null,
    /**
     * Amendment A35: present when the turn ended because the vendor's usage limit was reached, in
     * which case [stopReason] is `error`.
     */
    val limit: LimitStop? = null,
)

@Serializable
data class StatusPayload(val state: SessionState, val detail: String? = null)

/** A partial update of the session summary. */
@Serializable(with = MetaPayload.Serializer::class)
data class MetaPayload(
    val title: String? = null,
    val model: String? = null,
    val permissionMode: String? = null,
    val effort: String? = null,
    /**
     * Amendment A21: what the session's speed became. Null means the frame said nothing about it;
     * `SpeedChange.Standard` means the tier was turned off, which is `speed: null` on the wire and
     * is a change like any other.
     */
    val speed: SpeedChange? = null,
    val cwd: String? = null,
    val git: GitInfo? = null,
    val control: SessionControl? = null,
    val agentVersion: String? = null,
) {
    /** Every field but the speed, which an ordinary optional cannot read (A21). */
    @Serializable
    private class Fields(
        val title: String? = null,
        val model: String? = null,
        @SerialName("permission_mode") val permissionMode: String? = null,
        val effort: String? = null,
        val cwd: String? = null,
        val git: GitInfo? = null,
        val control: SessionControl? = null,
        @SerialName("agent_version") val agentVersion: String? = null,
    )

    object Serializer : KSerializer<MetaPayload> {
        private val fields = Fields.serializer()
        override val descriptor: SerialDescriptor = fields.descriptor

        override fun deserialize(decoder: Decoder): MetaPayload {
            val input = decoder as? JsonDecoder ?: throw SerializationException("MetaPayload reads JSON only")
            val element = input.decodeJsonElement()
            val read = input.json.decodeFromJsonElement(fields, element)
            // A key that is there and null turned the tier off; a key that is not there said nothing.
            val speed = (element as? JsonObject)?.get("speed")?.let { value ->
                if (value is JsonNull) SpeedChange.Standard
                else SpeedChange.Tier(value.stringValue ?: throw SerializationException("speed is a tier id or null"))
            }
            return MetaPayload(title = read.title, model = read.model, permissionMode = read.permissionMode,
                               effort = read.effort, speed = speed, cwd = read.cwd, git = read.git,
                               control = read.control, agentVersion = read.agentVersion)
        }

        override fun serialize(encoder: Encoder, value: MetaPayload) {
            val output = encoder as? JsonEncoder ?: throw SerializationException("MetaPayload writes JSON only")
            val written = output.json.encodeToJsonElement(
                fields, Fields(value.title, value.model, value.permissionMode, value.effort, value.cwd,
                               value.git, value.control, value.agentVersion)) as JsonObject
            // The speed goes where it stands among the properties, after the effort.
            val members = LinkedHashMap<String, JsonElement>()
            val speed = value.speed?.json
            for ((key, member) in written) {
                if (speed != null && "speed" !in members && key in afterSpeed) members["speed"] = speed
                members[key] = member
            }
            if (speed != null && "speed" !in members) members["speed"] = speed
            output.encodeJsonElement(JsonObject(members))
        }

        private val afterSpeed = setOf("cwd", "git", "control", "agent_version")
    }
}

/**
 * A message the user sent while a turn was running. `id` is the original `session.send` request
 * id, so the app can match its own optimistic row.
 */
@Serializable
data class QueuedMessage(
    val id: String,
    val text: String,
    val ts: Long,
    /**
     * Amendment A43: how many files the held message carries, absent when it carries none. The
     * files stay on the device and no frame brings them back, so such an entry can be removed but
     * not edited.
     */
    val attachments: Int? = null,
) {
    /** Whether the held message carries files. A count of zero is read as none, which is what the field's absence means. */
    val carriesFiles: Boolean get() = (attachments ?: 0) > 0
}

@Serializable
data class QueuePayload(val pending: List<QueuedMessage> = emptyList())

@Serializable
data class NoticePayload(val level: NoticeLevel = NoticeLevel.info, val text: String = "")

@Serializable
data class ErrorPayload(val message: String = "", val code: String? = null)
