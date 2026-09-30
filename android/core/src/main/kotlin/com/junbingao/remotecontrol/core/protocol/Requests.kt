package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.state.L10n
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Bounds the device enforces. The app checks them first so a rejected send never costs the user their draft. */
object RequestLimits {
    const val maxTextBytes = 64 * 1024
    const val maxAttachments = 8
    const val maxAttachmentBytes = 6 * 1024 * 1024
    const val historyPageSize = 200
    const val maxHistoryPageSize = 1000
}

/** An attachment travelling to the device, base64 encoded. */
class OutboundAttachment(
    /** Two files can share a name; a row and its remove button cannot. */
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val mime: String,
    val data: ByteArray,
) {
    val info: AttachmentInfo get() = AttachmentInfo(name = name, mime = mime, size = data.size)

    internal val json: JsonObject
        get() = jsonObjectOf("name" to name, "mime" to mime, "data_base64" to Base64.getEncoder().encodeToString(data))

    override fun equals(other: Any?): Boolean = other is OutboundAttachment && id == other.id &&
        name == other.name && mime == other.mime && data.contentEquals(other.data)

    override fun hashCode(): Int = listOf(id, name, mime, data.contentHashCode()).hashCode()

    override fun toString(): String = "OutboundAttachment(id=$id, name=$name, mime=$mime, size=${data.size})"
}

sealed class AttachmentError : Exception(null, null, false, false) {
    data class TooMany(val limit: Int) : AttachmentError()
    data class TooLarge(val name: String) : AttachmentError()
    data object TextTooLong : AttachmentError()

    val errorDescription: String
        get() = when (this) {
            is TooMany -> L10n.string("Attach at most %lld files to one message.", limit)
            is TooLarge -> L10n.string("%@ is larger than 6 MB. Attach a smaller file.", name)
            TextTooLong -> L10n.string("That message is longer than 64 KB. Shorten it or attach a file.")
        }

    override val message: String get() = errorDescription
}

/**
 * One request frame, ready to send. The `id` is chosen once and reused on a retry so a device can
 * recognise the duplicate and reply with the original result instead of sending the message
 * twice.
 */
data class GatewayRequest(
    val id: String = newRequestID(),
    val type: String,
    val body: JsonObject = JSONValue.emptyObject,
    /** Requests the gateway answers locally can time out faster than forwarded ones. */
    val expectsReply: Boolean = true,
) {
    val json: JsonObject
        get() {
            val frame = LinkedHashMap<String, JsonElement>()
            frame["type"] = JsonPrimitive(type)
            if (expectsReply) frame["id"] = JsonPrimitive(id)
            for ((key, value) in body) if (key != "type" && key != "id") frame[key] = value
            return JsonObject(frame)
        }

    fun encoded(): ByteArray = json.toString().encodeToByteArray()

    companion object {
        /** A fresh id, spelled as RCCore's `UUID().uuidString` spells it. */
        fun newRequestID(): String = UUID.randomUUID().toString().uppercase()

        fun pong(): GatewayRequest = GatewayRequest(type = "pong", expectsReply = false)

        fun subscribe(sessionID: String, sinceSeq: Int? = null): GatewayRequest =
            GatewayRequest(type = "session.subscribe",
                           body = jsonObjectOf("session_id" to sessionID, "since_seq" to sinceSeq).dropNulls())

        fun unsubscribe(sessionID: String): GatewayRequest =
            GatewayRequest(type = "session.unsubscribe", body = jsonObjectOf("session_id" to sessionID),
                           expectsReply = false)

        fun createSession(deviceID: String, agent: String, cwd: String,
                          model: String? = null, permissionMode: String? = null,
                          effort: String? = null, speed: SpeedChange? = null,
                          worktree: Boolean? = null,
                          firstMessage: String? = null, title: String? = null): GatewayRequest {
            val body = LinkedHashMap<String, JsonElement>()
            body["device_id"] = JsonPrimitive(deviceID)
            body["agent"] = JsonPrimitive(agent)
            body["cwd"] = JsonPrimitive(cwd)
            model?.let { body["model"] = JsonPrimitive(it) }
            permissionMode?.let { body["permission_mode"] = JsonPrimitive(it) }
            effort?.let { body["effort"] = JsonPrimitive(it) }
            speed?.let { body["speed"] = it.json }
            worktree?.let { body["worktree"] = JsonPrimitive(it) }
            if (!firstMessage.isNullOrEmpty()) body["first_message"] = JsonPrimitive(firstMessage)
            if (!title.isNullOrEmpty()) body["title"] = JsonPrimitive(title)
            return GatewayRequest(type = "session.create", body = JsonObject(body))
        }

        /**
         * Amendment A43: `queueTs` is the `ts` a queued entry had before it was taken out to be
         * edited. The device holds a queued message that carries it under that `ts`, in the place
         * the entry left; it never decides whether the message queues — `mode` does.
         */
        fun send(id: String = newRequestID(), sessionID: String, text: String,
                 attachments: List<OutboundAttachment> = emptyList(),
                 mode: SendMode = SendMode.auto, queueTs: Long? = null): GatewayRequest {
            if (text.encodeToByteArray().size > RequestLimits.maxTextBytes) throw AttachmentError.TextTooLong
            if (attachments.size > RequestLimits.maxAttachments) {
                throw AttachmentError.TooMany(RequestLimits.maxAttachments)
            }
            attachments.firstOrNull { it.data.size > RequestLimits.maxAttachmentBytes }?.let {
                throw AttachmentError.TooLarge(name = it.name)
            }
            val body = LinkedHashMap<String, JsonElement>()
            body["session_id"] = JsonPrimitive(sessionID)
            body["text"] = JsonPrimitive(text)
            body["mode"] = JsonPrimitive(mode.rawValue)
            if (attachments.isNotEmpty()) body["attachments"] = jsonOf(attachments.map { it.json })
            queueTs?.let { body["queue_ts"] = JsonPrimitive(it) }
            return GatewayRequest(id = id, type = "session.send", body = JsonObject(body))
        }

        fun stop(sessionID: String): GatewayRequest =
            GatewayRequest(type = "session.stop", body = jsonObjectOf("session_id" to sessionID))

        fun approve(sessionID: String, requestID: String, optionID: String, message: String? = null): GatewayRequest {
            val body = LinkedHashMap<String, JsonElement>()
            body["session_id"] = JsonPrimitive(sessionID)
            body["request_id"] = JsonPrimitive(requestID)
            body["option_id"] = JsonPrimitive(optionID)
            if (!message.isNullOrEmpty()) body["message"] = JsonPrimitive(message)
            return GatewayRequest(type = "session.approve", body = JsonObject(body))
        }

        fun answer(sessionID: String, requestID: String, answers: Map<String, QuestionAnswer>): GatewayRequest =
            GatewayRequest(type = "session.answer", body = jsonObjectOf(
                "session_id" to sessionID,
                "request_id" to requestID,
                "answers" to JSONValue.encode(answers),
            ))

        fun set(sessionID: String, model: String? = null, permissionMode: String? = null,
                effort: String? = null, speed: SpeedChange? = null, title: String? = null): GatewayRequest {
            val body = LinkedHashMap<String, JsonElement>()
            body["session_id"] = JsonPrimitive(sessionID)
            model?.let { body["model"] = JsonPrimitive(it) }
            permissionMode?.let { body["permission_mode"] = JsonPrimitive(it) }
            effort?.let { body["effort"] = JsonPrimitive(it) }
            speed?.let { body["speed"] = it.json }
            title?.let { body["title"] = JsonPrimitive(it) }
            return GatewayRequest(type = "session.set", body = JsonObject(body))
        }

        fun history(sessionID: String, beforeSeq: Int? = null,
                    limit: Int = RequestLimits.historyPageSize): GatewayRequest =
            GatewayRequest(type = "session.history", body = jsonObjectOf(
                "session_id" to sessionID,
                "limit" to limit.coerceIn(1, RequestLimits.maxHistoryPageSize),
                "before_seq" to beforeSeq,
            ).dropNulls())

        fun block(sessionID: String, blockID: String): GatewayRequest =
            GatewayRequest(type = "session.block", body = jsonObjectOf("session_id" to sessionID, "block_id" to blockID))

        /**
         * Amendment A27: what slash commands this session offers right now. The device answers
         * from the live process where there is one and from what it knows without starting one
         * where there is not, so the answer may be `[]`.
         */
        fun commands(sessionID: String): GatewayRequest =
            GatewayRequest(type = "session.commands", body = jsonObjectOf("session_id" to sessionID))

        /**
         * Amendment A27: run one. The request id is the block id the device echoes the command
         * under, exactly as A12 does for a message, so the row is in the transcript before the
         * request has left. The result is `{}`; what the command did arrives as events.
         */
        fun command(id: String = newRequestID(), sessionID: String, name: String,
                    argument: String? = null): GatewayRequest =
            GatewayRequest(id = id, type = "session.command", body = jsonObjectOf(
                "session_id" to sessionID,
                "name" to name,
                "argument" to argument?.ifEmpty { null },
            ).dropNulls())

        fun queueRemove(sessionID: String, queuedID: String): GatewayRequest =
            GatewayRequest(type = "session.queue_remove",
                           body = jsonObjectOf("session_id" to sessionID, "queued_id" to queuedID))

        /**
         * Amendment A35: schedule the resume of protocol 7.2 for `at`, or move the pending one
         * there. The device refuses a time less than a minute ahead or more than eight days out,
         * and one asked for while a turn runs or while the terminal controls the session.
         */
        fun resumeSet(sessionID: String, at: Instant): GatewayRequest =
            GatewayRequest(type = "session.resume_set",
                           body = jsonObjectOf("session_id" to sessionID, "at" to at.roundedMilliseconds))

        /** Amendment A35: remove the pending resume. Idempotent, so a second tap while the first is in flight is not an error. */
        fun resumeCancel(sessionID: String): GatewayRequest =
            GatewayRequest(type = "session.resume_cancel", body = jsonObjectOf("session_id" to sessionID))

        fun takeover(sessionID: String): GatewayRequest =
            GatewayRequest(type = "session.takeover", body = jsonObjectOf("session_id" to sessionID))

        fun archive(sessionID: String, archived: Boolean): GatewayRequest =
            GatewayRequest(type = "session.archive",
                           body = jsonObjectOf("session_id" to sessionID, "archived" to archived))

        fun delete(sessionID: String): GatewayRequest =
            GatewayRequest(type = "session.delete", body = jsonObjectOf("session_id" to sessionID))

        fun dirs(deviceID: String, path: String? = null): GatewayRequest =
            GatewayRequest(type = "device.dirs", body = jsonObjectOf("device_id" to deviceID, "path" to path).dropNulls())

        /**
         * Amendment A37: make one directory, `name`, inside `path` — a directory the device
         * listed. The reply is the new directory's own listing, as `device.dirs` would answer it,
         * so the picker stands in it at once.
         */
        fun mkdir(deviceID: String, path: String, name: String): GatewayRequest =
            GatewayRequest(type = "device.mkdir",
                           body = jsonObjectOf("device_id" to deviceID, "path" to path, "name" to name))

        fun git(deviceID: String, path: String): GatewayRequest =
            GatewayRequest(type = "device.git", body = jsonObjectOf("device_id" to deviceID, "path" to path))

        fun agents(deviceID: String): GatewayRequest =
            GatewayRequest(type = "device.agents", body = jsonObjectOf("device_id" to deviceID))

        /**
         * Amendment A22: fetch exactly the build the gateway serves, install it and restart. The
         * build travels with the request so a device that has already moved on refuses it rather
         * than reinstalling what it runs.
         */
        fun updateDevice(deviceID: String, build: String): GatewayRequest =
            GatewayRequest(type = "device.update", body = jsonObjectOf("device_id" to deviceID, "build" to build))

        // Terminals (amendment A38)

        /**
         * Start the person's login shell on a device, at the size the emulator is drawn at.
         * Output streams to this connection alone until it closes or another one attaches.
         */
        fun terminalOpen(deviceID: String, cols: Int, rows: Int): GatewayRequest =
            GatewayRequest(type = "terminal.open", body = jsonObjectOf(
                "device_id" to deviceID,
                "cols" to TerminalLimits.cols(cols),
                "rows" to TerminalLimits.rows(rows),
            ))

        /** Bytes as typed. The emulator produces the key sequences; nothing here interprets them, and nothing anywhere logs them. */
        fun terminalInput(deviceID: String, terminalID: String, data: ByteArray): GatewayRequest {
            if (data.size > TerminalLimits.maxInputBytes) throw TerminalInputError.TooLarge
            return GatewayRequest(type = "terminal.input", body = jsonObjectOf(
                "device_id" to deviceID,
                "terminal_id" to terminalID,
                "data" to Base64.getEncoder().encodeToString(data),
            ))
        }

        /** The app's view changed size, so the shell's does. */
        fun terminalResize(deviceID: String, terminalID: String, cols: Int, rows: Int): GatewayRequest =
            GatewayRequest(type = "terminal.resize", body = jsonObjectOf(
                "device_id" to deviceID,
                "terminal_id" to terminalID,
                "cols" to TerminalLimits.cols(cols),
                "rows" to TerminalLimits.rows(rows),
            ))

        /**
         * Take a terminal this account left running — after a lost socket, or from another app of
         * the same account. The reply carries the scrollback.
         */
        fun terminalAttach(deviceID: String, terminalID: String): GatewayRequest =
            GatewayRequest(type = "terminal.attach", body = jsonObjectOf("device_id" to deviceID, "terminal_id" to terminalID))

        /** End the shell. Idempotent, so leaving the screen twice is not an error. */
        fun terminalClose(deviceID: String, terminalID: String): GatewayRequest =
            GatewayRequest(type = "terminal.close", body = jsonObjectOf("device_id" to deviceID, "terminal_id" to terminalID))

        /** `Date.timeIntervalSince1970 * 1000`, rounded to the nearest millisecond. */
        private val Instant.roundedMilliseconds: Long
            get() = toEpochMilli() + if (nano % 1_000_000 >= 500_000) 1 else 0

        /** A literal's optional members that were given nothing are left out, as RCCore's `if let` leaves them. */
        private fun JsonObject.dropNulls(): JsonObject = JsonObject(filterValues { it !is JsonNull })
    }
}

/**
 * Amendment A21: what a request asks of a session's speed. An optional cannot say it, because
 * "leave the tier alone" and "put it back to standard" are different requests and the second one
 * is `speed: null` on the wire.
 */
sealed interface SpeedChange {
    /** The tier id, or null for the standard speed. */
    val id: String?

    data class Tier(override val id: String) : SpeedChange
    data object Standard : SpeedChange {
        override val id: String? get() = null
    }

    companion object {
        /** The tier id, or null for the standard speed. */
        operator fun invoke(id: String?): SpeedChange = id?.let(::Tier) ?: Standard
    }
}

/** The value a request or a `meta` event carries for the change: the tier's id, or null. */
internal val SpeedChange.json: JsonElement get() = id?.let(::JsonPrimitive) ?: JsonNull

/** The one thing the app refuses before the device does: a paste larger than one `terminal.input` may carry (A38). */
sealed class TerminalInputError : Exception(null, null, false, false) {
    data object TooLarge : TerminalInputError()

    val errorDescription: String get() = L10n.string("That is more than 64 KB of text. Paste less of it.")

    override val message: String get() = errorDescription
}
