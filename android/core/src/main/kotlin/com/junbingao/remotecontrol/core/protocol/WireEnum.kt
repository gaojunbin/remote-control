package com.junbingao.remotecontrol.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The wire protocol version this build speaks. A `hello` carrying any other value is a hard
 * error: the app refuses to guess the meaning of a frame.
 */
object RemoteProtocol {
    const val version = 1
}

/**
 * A lowercase string enumeration from the wire.
 *
 * Decoding never fails on a value this build has not heard of, so a newer gateway can add a state
 * or a tool kind without breaking older apps. Callers compare against the named constants and
 * fall back to [rawValue] for display.
 */
interface WireEnum {
    val rawValue: String
}

/** Reads and writes a [WireEnum] as the bare string it is on the wire, whatever the string. */
abstract class WireEnumSerializer<T : WireEnum>(name: String, private val make: (String) -> T) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): T = make(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.rawValue)
}

/** Machine platform of a device. */
@JvmInline
@Serializable(with = DevicePlatform.Serializer::class)
value class DevicePlatform(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val macos = DevicePlatform("macos")
        val linux = DevicePlatform("linux")
    }

    object Serializer : WireEnumSerializer<DevicePlatform>("DevicePlatform", ::DevicePlatform)
}

/**
 * Amendment A22: where a device is in an update an app asked for. A device that was never asked,
 * or that came back on its new build, is `idle`.
 */
@JvmInline
@Serializable(with = DeviceUpdateState.Serializer::class)
value class DeviceUpdateState(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val idle = DeviceUpdateState("idle")
        val updating = DeviceUpdateState("updating")
        val failed = DeviceUpdateState("failed")
    }

    object Serializer : WireEnumSerializer<DeviceUpdateState>("DeviceUpdateState", ::DeviceUpdateState)
}

/** Lifecycle of one session, as reported by the owning device. */
@JvmInline
@Serializable(with = SessionState.Serializer::class)
value class SessionState(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    /** `needs_approval` and `needs_input` are sub-states of a running turn. */
    val isWorking: Boolean get() = this == running || this == needsApproval || this == needsInput
    val isBlockedOnUser: Boolean get() = this == needsApproval || this == needsInput

    companion object {
        val starting = SessionState("starting")
        val idle = SessionState("idle")
        val running = SessionState("running")
        val needsApproval = SessionState("needs_approval")
        val needsInput = SessionState("needs_input")
        val error = SessionState("error")
        val stopped = SessionState("stopped")
        val readonly = SessionState("readonly")
    }

    object Serializer : WireEnumSerializer<SessionState>("SessionState", ::SessionState)
}

/** Who owns a session's input right now. */
@JvmInline
@Serializable(with = SessionControl.Serializer::class)
value class SessionControl(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val remote = SessionControl("remote")
        val terminal = SessionControl("terminal")

        /**
         * Amendment A10: a live CLI process owns the session and the device is attached to it,
         * so this app types into the same conversation.
         */
        val shared = SessionControl("shared")
        val none = SessionControl("none")
    }

    object Serializer : WireEnumSerializer<SessionControl>("SessionControl", ::SessionControl)
}

/** Amendment A10: how a device can attach to an agent's terminal sessions. */
@JvmInline
@Serializable(with = AgentAttach.Serializer::class)
value class AgentAttach(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        /** The Claude channel shim loaded by the CLI. */
        val channel = AgentAttach("channel")

        /** The Codex shared app-server daemon. */
        val daemon = AgentAttach("daemon")

        /**
         * Amendment A26: the device's own extension, which pi loads into every session it runs,
         * terminal sessions included.
         */
        val extension = AgentAttach("extension")

        /**
         * Amendment A28: Grok Build's leader, one backend process per machine that its TUI joins
         * and the device joins as another client of the same server.
         */
        val leader = AgentAttach("leader")
    }

    object Serializer : WireEnumSerializer<AgentAttach>("AgentAttach", ::AgentAttach)
}

/**
 * Amendment A10: what became of a message sent into a `shared` session.
 *
 * Amendment A19: a message the device is still holding is not a block at all, only a queue entry,
 * so there is no state here for one.
 */
@JvmInline
@Serializable(with = MessageDelivery.Serializer::class)
value class MessageDelivery(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        /** Injected into the live CLI session. */
        val delivered = MessageDelivery("delivered")

        /** Taken by the CLI as mid-turn data; the device will inject it again. */
        val absorbed = MessageDelivery("absorbed")
    }

    object Serializer : WireEnumSerializer<MessageDelivery>("MessageDelivery", ::MessageDelivery)
}

/** Who created a session, or what triggered a turn or message. */
@JvmInline
@Serializable(with = EventSource.Serializer::class)
value class EventSource(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    /**
     * Amendment A30: a turn another agent's message started is not this app's doing, so
     * everything that asks "did I start this?" reads it as it reads a turn typed at the terminal.
     */
    val isElsewhere: Boolean get() = this == terminal || this == agent

    companion object {
        val remote = EventSource("remote")
        val terminal = EventSource("terminal")
        val queue = EventSource("queue")
        val policy = EventSource("policy")

        /**
         * Amendment A30: words the Claude CLI filed as a user turn that no person typed — a
         * teammate's message, a background task's notification. They are neither the person nor
         * the assistant, and the transcript says so.
         */
        val agent = EventSource("agent")

        /**
         * Amendment A35: the prompt the device sent for the person once a usage limit reset
         * (7.2). It is the person's own message, sent on their standing instruction, so the
         * transcript draws it in their bubble and the status line reads the turn it starts as a
         * remote one.
         */
        val resume = EventSource("resume")
    }

    object Serializer : WireEnumSerializer<EventSource>("EventSource", ::EventSource)
}

/** Coarse classification of a tool call, used to pick an icon and a summary. */
@JvmInline
@Serializable(with = ToolKind.Serializer::class)
value class ToolKind(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val shell = ToolKind("shell")
        val read = ToolKind("read")
        val edit = ToolKind("edit")
        val write = ToolKind("write")
        val search = ToolKind("search")
        val web = ToolKind("web")
        val mcp = ToolKind("mcp")
        val subagent = ToolKind("subagent")
        val todo = ToolKind("todo")
        val other = ToolKind("other")

        /**
         * Classify a tool from its name, for the rare event that omits `tool_kind`. The wire
         * field wins whenever it is present (amendment A1).
         */
        fun derived(fromTool: String): ToolKind {
            val name = fromTool.lowercase()
            if (name.startsWith("mcp__")) return mcp
            return when (name) {
                "bash", "shell", "terminal", "run", "exec", "command" -> shell
                "read", "cat", "view", "notebookread", "readfile" -> read
                "edit", "multiedit", "notebookedit", "applypatch", "apply_patch", "update" -> edit
                "write", "create", "writefile" -> write
                "grep", "glob", "search", "find", "ls", "list" -> search
                "webfetch", "websearch", "fetch", "browser" -> web
                "task", "agent", "subagent", "dispatch" -> subagent
                "todowrite", "todo", "todos", "plan" -> todo
                else -> other
            }
        }
    }

    object Serializer : WireEnumSerializer<ToolKind>("ToolKind", ::ToolKind)
}

@JvmInline
@Serializable(with = ToolStatus.Serializer::class)
value class ToolStatus(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    val isFinished: Boolean get() = this != running

    companion object {
        val running = ToolStatus("running")
        val succeeded = ToolStatus("succeeded")
        val failed = ToolStatus("failed")
        val cancelled = ToolStatus("cancelled")
    }

    object Serializer : WireEnumSerializer<ToolStatus>("ToolStatus", ::ToolStatus)
}

/** Lifecycle of an approval or a question card. */
@JvmInline
@Serializable(with = RequestStatus.Serializer::class)
value class RequestStatus(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    val isActionable: Boolean get() = this == pending

    companion object {
        val pending = RequestStatus("pending")
        val resolved = RequestStatus("resolved")
        val expired = RequestStatus("expired")
    }

    object Serializer : WireEnumSerializer<RequestStatus>("RequestStatus", ::RequestStatus)
}

/** Visual weight the device asks for on an approval option. Ids stay opaque. */
@JvmInline
@Serializable(with = OptionStyle.Serializer::class)
value class OptionStyle(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val primary = OptionStyle("primary")
        val secondary = OptionStyle("secondary")
        val danger = OptionStyle("danger")
    }

    object Serializer : WireEnumSerializer<OptionStyle>("OptionStyle", ::OptionStyle)
}

@JvmInline
@Serializable(with = NoticeLevel.Serializer::class)
value class NoticeLevel(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val info = NoticeLevel("info")
        val warn = NoticeLevel("warn")
        val error = NoticeLevel("error")
    }

    object Serializer : WireEnumSerializer<NoticeLevel>("NoticeLevel", ::NoticeLevel)
}

@JvmInline
@Serializable(with = StopReason.Serializer::class)
value class StopReason(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val completed = StopReason("completed")
        val interrupted = StopReason("interrupted")
        val error = StopReason("error")
    }

    object Serializer : WireEnumSerializer<StopReason>("StopReason", ::StopReason)
}

@JvmInline
@Serializable(with = TodoStatus.Serializer::class)
value class TodoStatus(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val pending = TodoStatus("pending")
        val inProgress = TodoStatus("in_progress")
        val completed = TodoStatus("completed")
    }

    object Serializer : WireEnumSerializer<TodoStatus>("TodoStatus", ::TodoStatus)
}

/** How the device should treat a `session.send` that arrives during a turn. */
@JvmInline
@Serializable(with = SendMode.Serializer::class)
value class SendMode(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val auto = SendMode("auto")
        val queue = SendMode("queue")
        val interrupt = SendMode("interrupt")
    }

    object Serializer : WireEnumSerializer<SendMode>("SendMode", ::SendMode)
}

/** What the device did with an accepted `session.send`. */
@JvmInline
@Serializable(with = SendAcceptance.Serializer::class)
value class SendAcceptance(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val sent = SendAcceptance("sent")
        val queued = SendAcceptance("queued")
        val steered = SendAcceptance("steered")
    }

    object Serializer : WireEnumSerializer<SendAcceptance>("SendAcceptance", ::SendAcceptance)
}

/** Optional device behaviour an app must check before offering an affordance. */
@JvmInline
@Serializable(with = AgentCapability.Serializer::class)
value class AgentCapability(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val worktree = AgentCapability("worktree")
        val takeover = AgentCapability("takeover")
        val interrupt = AgentCapability("interrupt")
        val queue = AgentCapability("queue")
        val steer = AgentCapability("steer")
        val attachments = AgentCapability("attachments")
        val effort = AgentCapability("effort")
        val history = AgentCapability("history")

        /**
         * Amendment A27: this agent's sessions can list and run slash commands from an app.
         * Codex, Grok Build and pi carry it; Claude does not, because its channel carries user
         * text and nothing else.
         */
        val commands = AgentCapability("commands")
    }

    object Serializer : WireEnumSerializer<AgentCapability>("AgentCapability", ::AgentCapability)
}

/** Category carried by a push payload. */
@JvmInline
@Serializable(with = PushKind.Serializer::class)
value class PushKind(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        val needsApproval = PushKind("needs_approval")
        val needsInput = PushKind("needs_input")
        val turnCompleted = PushKind("turn_completed")
        val error = PushKind("error")

        /**
         * Amendment A35: the three kinds that follow a `resume` event — the session was paused
         * and a resume scheduled, the resume ran, or it could not. The time is never in the push;
         * the app reads it from `Session`.
         */
        val limitReached = PushKind("limit_reached")
        val resumed = PushKind("resumed")
        val resumeDropped = PushKind("resume_dropped")

        /**
         * Amendment A47: the account's count of red dots changed and no other push carried it. It
         * sets the app icon's badge and shows nothing.
         */
        val badge = PushKind("badge")
    }

    object Serializer : WireEnumSerializer<PushKind>("PushKind", ::PushKind)
}
