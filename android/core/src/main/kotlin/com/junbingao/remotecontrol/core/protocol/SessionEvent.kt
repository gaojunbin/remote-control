package com.junbingao.remotecontrol.core.protocol

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
 * The typed body of a session event, selected by the wire `kind`.
 *
 * A kind this build does not know is preserved verbatim as [Unknown], so a newer device can emit
 * a new block type without the app losing the event or the ordering around it.
 */
sealed interface SessionEventBody {
    data class UserMessage(val payload: UserMessagePayload) : SessionEventBody
    data class AssistantText(val payload: StreamTextPayload) : SessionEventBody
    data class Thinking(val payload: StreamTextPayload) : SessionEventBody
    data class ToolCall(val payload: ToolCallPayload) : SessionEventBody
    data class Todos(val payload: TodosPayload) : SessionEventBody
    data class Approval(val payload: ApprovalPayload) : SessionEventBody
    data class Question(val payload: QuestionPayload) : SessionEventBody
    data class TurnStarted(val payload: TurnStartedPayload) : SessionEventBody
    data class TurnCompleted(val payload: TurnCompletedPayload) : SessionEventBody
    data class Status(val payload: StatusPayload) : SessionEventBody
    data class Meta(val payload: MetaPayload) : SessionEventBody
    data class Queue(val payload: QueuePayload) : SessionEventBody
    data class Notice(val payload: NoticePayload) : SessionEventBody
    data class Error(val payload: ErrorPayload) : SessionEventBody

    /** Amendment A35: what the device did about a resume after a usage limit. */
    data class Resume(val payload: ResumePayload) : SessionEventBody
    data class Unknown(val kind: String, val raw: JsonElement) : SessionEventBody
}

/**
 * One entry in a session timeline, as produced by the owning device.
 *
 * `seq` is per-session and monotonically increasing; it is the replay cursor and it wins over `ts`
 * for display order, because `ts` is the device clock.
 */
@Serializable(with = SessionEvent.Serializer::class)
data class SessionEvent(
    val seq: Int,
    val ts: Long,
    val kind: String,
    val blockID: String? = null,
    val parentBlockID: String? = null,
    /**
     * Amendment A8: the seq at which this block first appeared. A block that keeps streaming gets
     * a rising `seq` but keeps its place in the transcript, which is what this pins down.
     */
    val firstSeq: Int? = null,
    val body: SessionEventBody,
    /** The frame exactly as received, so unknown fields survive a round trip. */
    val raw: JsonElement = JSONValue.emptyObject,
) {
    val id: Int get() = seq

    /** Where this event's block belongs in the transcript. */
    val orderSeq: Int get() = firstSeq ?: seq

    /** Block events replace each other by `block_id`; non-block events stand alone. */
    val isBlock: Boolean get() = blockID != null

    /** True while this event still expects further deltas for the same block. */
    val isStreaming: Boolean
        get() = when (body) {
            is SessionEventBody.AssistantText -> !body.payload.done
            is SessionEventBody.Thinking -> !body.payload.done
            is SessionEventBody.ToolCall -> body.payload.status == ToolStatus.running
            else -> false
        }

    val userMessage: UserMessagePayload? get() = (body as? SessionEventBody.UserMessage)?.payload
    val streamText: StreamTextPayload?
        get() = when (body) {
            is SessionEventBody.AssistantText -> body.payload
            is SessionEventBody.Thinking -> body.payload
            else -> null
        }
    val toolCall: ToolCallPayload? get() = (body as? SessionEventBody.ToolCall)?.payload
    val approval: ApprovalPayload? get() = (body as? SessionEventBody.Approval)?.payload
    val question: QuestionPayload? get() = (body as? SessionEventBody.Question)?.payload
    val todos: TodosPayload? get() = (body as? SessionEventBody.Todos)?.payload
    val meta: MetaPayload? get() = (body as? SessionEventBody.Meta)?.payload
    val resume: ResumePayload? get() = (body as? SessionEventBody.Resume)?.payload

    companion object {
        const val userMessageKind = "user_message"
        const val assistantTextKind = "assistant_text"
        const val thinkingKind = "thinking"
        const val toolCallKind = "tool_call"
        const val todosKind = "todos"
        const val approvalKind = "approval"
        const val questionKind = "question"
        const val turnStartedKind = "turn_started"
        const val turnCompletedKind = "turn_completed"
        const val statusKind = "status"
        const val metaKind = "meta"
        const val queueKind = "queue"
        const val noticeKind = "notice"
        const val errorKind = "error"
        const val resumeKind = "resume"
    }

    /** The fields every event carries, read as strictly as RCCore reads them: `kind` is required. */
    @Serializable
    private class Envelope(
        val seq: Int = 0,
        val ts: Long = 0,
        val kind: String,
        @SerialName("block_id") val blockID: String? = null,
        @SerialName("parent_block_id") val parentBlockID: String? = null,
        @SerialName("first_seq") val firstSeq: Int? = null,
    )

    object Serializer : KSerializer<SessionEvent> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SessionEvent")

        override fun deserialize(decoder: Decoder): SessionEvent {
            val input = decoder as? JsonDecoder ?: throw SerializationException("SessionEvent reads JSON only")
            val raw = input.decodeJsonElement()
            val envelope = raw.decode(Envelope.serializer())
            val body = when (envelope.kind) {
                userMessageKind -> SessionEventBody.UserMessage(raw.decode())
                assistantTextKind -> SessionEventBody.AssistantText(raw.decode())
                thinkingKind -> SessionEventBody.Thinking(raw.decode())
                toolCallKind -> SessionEventBody.ToolCall(raw.decode())
                todosKind -> SessionEventBody.Todos(raw.decode())
                approvalKind -> SessionEventBody.Approval(raw.decode())
                questionKind -> SessionEventBody.Question(raw.decode())
                turnStartedKind -> SessionEventBody.TurnStarted(raw.decode())
                turnCompletedKind -> SessionEventBody.TurnCompleted(raw.decode())
                statusKind -> SessionEventBody.Status(raw.decode())
                metaKind -> SessionEventBody.Meta(raw.decode())
                queueKind -> SessionEventBody.Queue(raw.decode())
                noticeKind -> SessionEventBody.Notice(raw.decode())
                errorKind -> SessionEventBody.Error(raw.decode())
                resumeKind -> SessionEventBody.Resume(raw.decode())
                else -> SessionEventBody.Unknown(kind = envelope.kind, raw = raw)
            }
            return SessionEvent(seq = envelope.seq, ts = envelope.ts, kind = envelope.kind,
                                blockID = envelope.blockID, parentBlockID = envelope.parentBlockID,
                                firstSeq = envelope.firstSeq, body = body, raw = raw)
        }

        /**
         * The typed payload, then every field of the raw frame this build does not model, which
         * still belongs on the wire — under the envelope, in the order the frame arrived in.
         */
        override fun serialize(encoder: Encoder, value: SessionEvent) {
            val output = encoder as? JsonEncoder ?: throw SerializationException("SessionEvent writes JSON only")
            val payload = payloadObject(value.body)
            val members = LinkedHashMap<String, JsonElement>()
            members["seq"] = JsonPrimitive(value.seq)
            members["ts"] = JsonPrimitive(value.ts)
            members["kind"] = JsonPrimitive(value.kind)
            value.firstSeq?.let { members["first_seq"] = JsonPrimitive(it) }
            value.blockID?.let { members["block_id"] = JsonPrimitive(it) }
            value.parentBlockID?.let { members["parent_block_id"] = JsonPrimitive(it) }
            for ((key, raw) in value.raw.objectValue ?: JSONValue.emptyObject) {
                if (key in envelopeKeys) continue
                members[key] = payload[key] ?: raw
            }
            for ((key, typed) in payload) {
                if (key in envelopeKeys || key in members) continue
                members[key] = typed
            }
            output.encodeJsonElement(JsonObject(members))
        }

        private val envelopeKeys = setOf("seq", "ts", "kind", "block_id", "parent_block_id", "first_seq")

        private fun payloadObject(body: SessionEventBody): JsonObject {
            val value: JsonElement = when (body) {
                is SessionEventBody.UserMessage -> JSONValue.encode(body.payload)
                is SessionEventBody.AssistantText -> JSONValue.encode(body.payload)
                is SessionEventBody.Thinking -> JSONValue.encode(body.payload)
                is SessionEventBody.ToolCall -> JSONValue.encode(body.payload)
                is SessionEventBody.Todos -> JSONValue.encode(body.payload)
                is SessionEventBody.Approval -> JSONValue.encode(body.payload)
                is SessionEventBody.Question -> JSONValue.encode(body.payload)
                is SessionEventBody.TurnStarted -> JSONValue.encode(body.payload)
                is SessionEventBody.TurnCompleted -> JSONValue.encode(body.payload)
                is SessionEventBody.Status -> JSONValue.encode(body.payload)
                is SessionEventBody.Meta -> JSONValue.encode(body.payload)
                is SessionEventBody.Queue -> JSONValue.encode(body.payload)
                is SessionEventBody.Notice -> JSONValue.encode(body.payload)
                is SessionEventBody.Error -> JSONValue.encode(body.payload)
                is SessionEventBody.Resume -> JSONValue.encode(body.payload)
                is SessionEventBody.Unknown -> body.raw
            }
            return value.objectValue ?: JSONValue.emptyObject
        }
    }
}
