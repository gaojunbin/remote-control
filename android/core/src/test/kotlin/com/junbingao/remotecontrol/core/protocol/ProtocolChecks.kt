package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.FixtureSource
import java.io.File
import kotlin.test.Test

/**
 * `ios/Verification/ProtocolChecks.swift`, the first half: every fixture in `protocol/fixtures`
 * decoded, and the decoding rules the screens rely on. Decoding is the contract test for a
 * client: the schema itself is validated by `protocol/scripts/validate_fixtures.py`. The frames,
 * the HTTP bodies and the requests are in `ProtocolFrameChecks` and `ProtocolRequestChecks`.
 */
class ProtocolChecks {
    /**
     * Every fixture must parse, and every fixture the app consumes must decode into a typed model
     * and survive a re-encode without losing a field.
     */
    @Test
    fun decode() {
        val checks = CheckRunner("protocol")
        val files = FixtureSource.files(FixtureSource.fixtures)
        checks.expect(files.isNotEmpty(), "protocol/fixtures contains JSON files")
        for (file in files) decode(file, checks)
        println("fixtures: ${files.size} under ${FixtureSource.fixtures.path}, ${checks.passed} checks passed")
        checks.assertAll()
    }

    private fun decode(file: File, checks: CheckRunner) {
        val name = FixtureSource.label(file)
        val json = runCatching { JSONValue.parse(file.readBytes()) }.getOrNull()
        if (json == null) {
            checks.expect(false, "$name parses as JSON")
            return
        }
        checks.noThrow("$name survives a JSONValue round trip") {
            val again = JSONValue.parse(json.toString().encodeToByteArray())
            if (again != json) throw ProtocolFailure.Malformed(name)
        }
        if (name.startsWith("events/")) {
            checks.noThrow("$name round-trips as SessionEvent") {
                val event = json.decode<SessionEvent>()
                val encoded = JSONValue.encode(event)
                val again = encoded.decode<SessionEvent>()
                if (again.seq != event.seq || again.kind != event.kind || again.body != event.body ||
                    again.blockID != event.blockID || again.parentBlockID != event.parentBlockID) {
                    throw ProtocolFailure.Malformed("$name changed on re-encoding")
                }
                for (key in json.objectValue?.keys.orEmpty()) {
                    if (encoded[key] == null) throw ProtocolFailure.Malformed("$name lost field $key")
                }
            }
        }
        if (name.startsWith("objects/")) {
            checks.noThrow("$name decodes as the object it names") {
                if ("/session." in name) json.decode<Session>()
                if ("/agent." in name) json.decode<AgentInfo>()
            }
        }
        if (name.startsWith("app/")) {
            checks.noThrow("$name decodes as an app frame or request") {
                val type = json["type"]?.stringValue ?: throw ProtocolFailure.Malformed("$name has no type")
                // Frames the gateway sends decode into AppFrame; frames the app sends are covered
                // by the request-builder checks.
                if (type in inboundTypes) AppFrame(json = json)
            }
        }
    }

    private val inboundTypes = setOf(
        "hello", "device.updated", "device.removed", "session.updated", "session.removed",
        "session.event", "pairing.progress", "preferences.updated", "ping", "reply",
        // Amendment A38: the two frames a terminal produces.
        "terminal.output", "terminal.exited",
    )

    @Test
    fun objects() {
        val checks = CheckRunner("protocol")
        val payload = (AppFrame(json = FixtureSource.json("app/hello.json")) as AppFrame.Hello).hello
        checks.equal(payload.protocolVersion, RemoteProtocol.version, "hello carries protocol 1")
        checks.equal(payload.devices.size, 2, "hello carries both devices")
        checks.equal(payload.sessions.size, 2, "hello carries both sessions")
        checks.expect(payload.stt.enabled, "hello reports gateway transcription")
        checks.equal(payload.stt.languages, listOf("auto"), "and no language to choose: its provider detects it (A44)")

        val mac = payload.devices.first()
        val claude = checkNotNull(mac.agent("claude")) { "the first device exposes Claude" }
        checks.expect(claude.supports(AgentCapability.takeover), "Claude advertises takeover")
        checks.expect(!claude.supports(AgentCapability.steer), "Claude does not advertise steering")
        checks.expect(mac.agent("codex")?.supports(AgentCapability.steer) == true, "Codex advertises steering")
        checks.equal(claude.modelLabel("claude-sonnet-4-5"), "Sonnet 4.5", "a model id resolves to its label")
        checks.equal(claude.modelLabel("unknown-model"), "unknown-model", "an unknown model falls back to its id")
        checks.equal(claude.permissionModeLabel("acceptEdits"), "Auto-accept edits", "permission modes are labelled")
        checks.equal(mac.availableAgents.size, 2, "both agents are available")
        checks.expect(!payload.devices[1].online, "the offline device is reported offline")
        checks.expect(payload.devices[1].latencyMS == null, "a null latency decodes as null")

        // Amendment A2: token totals are required, cost and context are not.
        val session = payload.sessions[0]
        checks.equal(session.usage?.totalTokens, 54_330, "usage totals decode")
        checks.equal(session.usage?.costUSD, 0.42, "a reported cost decodes")
        checks.expect(payload.sessions[1].usage?.costUSD == null, "a missing cost decodes as null")
        checks.equal(payload.sessions[1].usage?.totalTokens, 30_440, "totals decode without a cost")
        checks.equal(session.folderName, "gateway", "the folder name is derived from the working directory")

        val completed = (FixtureSource.json("events/turn_completed.no_cost.json").decode<SessionEvent>().body
            as SessionEventBody.TurnCompleted).payload
        checks.expect(completed.usage?.costUSD == null, "a turn without a cost decodes")
        checks.expect((completed.usage?.totalTokens ?: 0) > 0, "a turn without a cost still reports totals")
        checks.assertAll()
    }

    /**
     * Amendment A10: `shared` sessions, the attachment fields on an agent and the delivery state
     * of a message the device is holding.
     */
    @Test
    fun sharedControl() {
        val checks = CheckRunner("protocol")
        val idle = FixtureSource.json("objects/session.shared-idle.json").decode<Session>()
        checks.equal(idle.control, SessionControl.shared, "an attached session reports shared control")
        checks.expect(idle.isAttached, "and the app reads it as attached")
        checks.expect(!idle.isControlledByTerminal, "an attached session is not locked to the terminal")
        checks.equal(idle.state, SessionState.idle, "an attached session idles rather than going read-only")
        checks.equal(idle.origin, EventSource.terminal, "the CLI still started it")

        val running = FixtureSource.json("objects/session.shared-running.json").decode<Session>()
        checks.expect(running.isAttached, "a running attached session is still attached")
        checks.expect(running.state.isWorking, "and reports a turn in progress")
        checks.equal(running.queued, 1, "a held message counts against the queue")

        val agent = FixtureSource.json("objects/agent.claude-attach.json").decode<AgentInfo>()
        checks.equal(agent.attach, AgentAttach.channel, "Claude attaches through a channel")
        checks.expect(agent.attachReady, "the device says the shim is installed")
        // Amendment A42: the pseudo-terminal types Escape, so the turn can be stopped.
        checks.expect(agent.sharedInterrupt, "the shim's pseudo-terminal can stop a running turn")
        checks.expect(!agent.sharedAttachments, "nor hand it bytes")
        // Amendment A40: the shim's pseudo-terminal is typed into, so two of the four settings are
        // the device's and the other two are not.
        checks.expect(agent.sharedSettings, "but the terminal it owns can be typed into")
        checks.equal(agent.sharedSettingsKeys, listOf("model", "effort"), "for the two settings a command sets")
        checks.expect(agent.shares(SharedSetting.model) && agent.shares(SharedSetting.effort),
                      "which are the two an app draws as pickers")
        checks.expect(!agent.shares(SharedSetting.permissionMode) && !agent.shares(SharedSetting.speed),
                      "and the rest stay what the terminal set (A17)")
        checks.expect(agent.supports(AgentCapability.commands), "the same typing runs /compact (A27)")
        checks.expect(agent.supports(AgentCapability.takeover), "takeover is unchanged by the attachment")
        checks.noThrow("the attachment fields survive a re-encode") {
            val again = JSONValue.encode(agent).decode<AgentInfo>()
            if (again.attach != agent.attach || again.attachReady != agent.attachReady ||
                again.sharedInterrupt != agent.sharedInterrupt || again.sharedSettings != agent.sharedSettings ||
                again.sharedSettingsKeys != agent.sharedSettingsKeys ||
                again.sharedAttachments != agent.sharedAttachments) {
                throw ProtocolFailure.Malformed("attachment fields changed")
            }
        }

        // An agent that says nothing about attaching is not attachable.
        val hello = (AppFrame(json = FixtureSource.json("app/hello.json")) as AppFrame.Hello).hello
        val claude = checkNotNull(hello.devices.first().agent("claude"))
        checks.expect(claude.attach == null, "an agent without an attachment reports none")
        checks.expect(!claude.attachReady, "and is not ready to attach")
        checks.expect(!claude.sharedInterrupt, "and does not claim a shared interrupt")
        checks.expect(!claude.sharedSettings, "nor shared settings")
        checks.expect(!claude.sharedAttachments, "nor shared attachments")

        for ((file, expected) in listOf("events/user_message.delivered.json" to MessageDelivery.delivered,
                                        "events/user_message.absorbed.json" to MessageDelivery.absorbed)) {
            val event = FixtureSource.json(file).decode<SessionEvent>()
            val message = event.userMessage
            if (message == null) {
                checks.expect(false, "$file decodes as a user message")
                continue
            }
            checks.equal(message.delivery, expected, "$file carries its delivery state")
            checks.equal(message.source, EventSource.remote, "a message sent from an app stays remote")
            checks.noThrow("$file keeps its delivery on a re-encode") {
                if (JSONValue.encode(event)["delivery"]?.stringValue != expected.rawValue) {
                    throw ProtocolFailure.Malformed("$file lost its delivery")
                }
            }
        }

        // Amendment A19: a message the device is still holding is a queue entry and no block at
        // all, so `pending` is not a delivery state any more and the block that does appear is
        // placed after the turn it waited for.
        checks.expect(MessageDelivery(rawValue = "pending") != MessageDelivery.delivered,
                      "a device that still says pending is not mistaken for a delivered message")
        val delivered = FixtureSource.json("events/user_message.delivered.json").decode<SessionEvent>()
        checks.equal(delivered.orderSeq, delivered.firstSeq,
                     "an injected message is drawn where its block started, not where it landed")

        // An ordinary prompt says nothing about delivery.
        checks.expect(FixtureSource.json("events/user_message.json").decode<SessionEvent>().userMessage?.delivery == null,
                      "a normal prompt has no delivery state")

        val sharedPending = checkNotNull(FixtureSource.json("events/approval.shared-pending.json").decode<SessionEvent>().approval)
        checks.equal(sharedPending.options.map { it.id }, listOf("allow", "deny"), "a relayed request offers exactly allow and deny")
        checks.expect(sharedPending.diff == null, "the relay does not provide a diff")
        checks.equal(sharedPending.input?.get("tool_name")?.stringValue, "Bash", "the relay passes the tool name through")
        checks.expect(sharedPending.input?.get("input_preview") != null, "and a preview of the input")

        val sharedTerminal = checkNotNull(FixtureSource.json("events/approval.shared-terminal.json").decode<SessionEvent>().approval)
        checks.equal(sharedTerminal.status, RequestStatus.resolved, "an approval answered in the terminal resolves")
        checks.equal(sharedTerminal.decision?.by, EventSource.terminal, "and is attributed to the terminal")

        // A value from a newer device decodes without pretending to be known.
        val unknownControl = SessionControl(rawValue = "attached")
        checks.expect(unknownControl != SessionControl.shared && unknownControl != SessionControl.terminal,
                      "an unrecognised control is not mistaken for a known one")
        val unknownDelivery = MessageDelivery(rawValue = "queued")
        checks.expect(unknownDelivery != MessageDelivery.delivered && unknownDelivery != MessageDelivery.absorbed,
                      "an unrecognised delivery is not mistaken for a known one")
        checks.assertAll()
    }

    /**
     * Amendment A11: Codex through the shared app-server daemon. The attachment carries the
     * settings, the attachments and the interrupt, and a request answered by whoever else holds
     * the thread comes back with an option id the block never offered.
     */
    @Test
    fun codexDaemon() {
        val checks = CheckRunner("protocol")
        val agent = FixtureSource.json("objects/agent.codex-daemon.json").decode<AgentInfo>()
        checks.equal(agent.attach, AgentAttach.daemon, "Codex attaches through the app-server daemon")
        checks.expect(agent.attachReady, "the device handshook with the daemon socket")
        checks.expect(agent.sharedInterrupt, "the daemon relays an interrupt")
        checks.expect(agent.sharedSettings, "and the thread settings")
        checks.expect(agent.sharedAttachments, "and image inputs")
        // Amendment A40: an attachment that names no subset carries all four settings, which is
        // how every attachment but Claude's reads.
        checks.equal(agent.sharedSettingsKeys, null, "it names no subset of the settings")
        checks.expect(SharedSetting.allCases.all(agent::shares), "so all four of them are the app's to change")
        checks.expect(!agent.supports(AgentCapability.takeover), "there is nothing to take over from")
        checks.expect(agent.supports(AgentCapability.effort), "effort is one of the settings it relays")
        checks.noThrow("the two booleans survive a re-encode") {
            val encoded = JSONValue.encode(agent)
            if (encoded["shared_settings"]?.boolValue != true || encoded["shared_attachments"]?.boolValue != true) {
                throw ProtocolFailure.Malformed("the shared booleans changed")
            }
        }

        // Absent means false, so a device that never heard of A11 is safe.
        val bare = jsonObjectOf("agent" to "codex", "available" to true).decode<AgentInfo>()
        checks.expect(!bare.sharedSettings, "an agent that says nothing keeps its settings local")
        checks.expect(SharedSetting.allCases.none(bare::shares),
                      "so none of the four is shared, whatever the keys would have said")
        checks.expect(!bare.sharedAttachments, "and takes no attachments")

        // A boolean field that is not a boolean is refused, not coerced.
        val broken = FixtureSource.invalidJSON("objects.agent__shared_settings_not_boolean.json")
        checks.expect(runCatching { broken.decode<AgentInfo>() }.isFailure,
                      "a non-boolean shared_settings is refused rather than coerced")

        val session = FixtureSource.json("objects/session.codex-shared-running.json").decode<Session>()
        checks.equal(session.agent, "codex", "the shared thread runs Codex")
        checks.expect(session.isAttached, "the device is attached to it")
        checks.equal(session.origin, EventSource.terminal, "the terminal started it")
        checks.expect(session.state.isWorking, "and a turn is running")

        val pending = checkNotNull(FixtureSource.json("events/approval.codex-shared-pending.json").decode<SessionEvent>().approval)
        checks.equal(pending.options.map { it.id }, listOf("allow", "allow_session", "allow_always", "deny"),
                     "the daemon's four decisions arrive as four options")
        checks.equal(pending.primaryOption?.id, "allow", "with one primary")
        checks.equal(pending.dangerOption?.id, "deny", "and one danger")
        checks.equal(pending.otherOptions.map { it.id }, listOf("allow_session", "allow_always"),
                     "and the rest stacked between them")
        checks.equal(pending.input?.get("cwd")?.stringValue, "/Users/me/dev/web", "a command request carries its working directory")
        checks.expect(pending.input?.get("command_actions") != null, "and what the command does")

        val elsewhere = checkNotNull(FixtureSource.json("events/approval.codex-elsewhere.json").decode<SessionEvent>().approval)
        checks.equal(elsewhere.status, RequestStatus.resolved, "a request answered elsewhere resolves")
        checks.equal(elsewhere.decision?.optionID, ApprovalPayload.elsewhereOptionID, "with the reserved option id")
        checks.equal(elsewhere.decision?.by, EventSource.terminal, "attributed to the terminal")
        checks.expect(elsewhere.resolvedOptionLabel == null, "and nothing to name, so the card says only who answered")

        // An id the block never offered still names itself rather than blanking.
        val unknown = ApprovalPayload(
            requestID = "r", tool = "shell", kind = ToolKind.shell, title = "t",
            options = listOf(ApprovalOption(id = "allow", label = "Allow", style = OptionStyle.primary)),
            status = RequestStatus.resolved, decision = ApprovalDecision(optionID = "allow_next_week", by = EventSource.terminal))
        checks.equal(unknown.resolvedOptionLabel, "allow_next_week", "an unrecognised option id renders verbatim")
        checks.assertAll()
    }

    /**
     * Amendment A28: Grok Build through the leader its terminals join. The leader relays an
     * interrupt and the session settings to every client of it, and its prompts take no images, so
     * one of the five fields is false.
     */
    @Test
    fun grokLeader() {
        val checks = CheckRunner("protocol")
        val agent = FixtureSource.json("objects/agent.grok.json").decode<AgentInfo>()
        checks.equal(agent.attach, AgentAttach.leader, "Grok Build attaches through its leader")
        checks.equal(AgentAttach(rawValue = "leader"), AgentAttach.leader, "which the app reads by that name")
        checks.expect(agent.attachReady, "the device reports the person's configuration, not a handshake")
        checks.expect(agent.sharedInterrupt, "session/cancel from any client stops the turn")
        checks.expect(agent.sharedSettings, "and set_config_option retunes it for everyone")
        checks.expect(!agent.sharedAttachments, "while a Grok prompt carries no images")
        checks.expect(!agent.supports(AgentCapability.takeover), "there is nothing to take over from")
        checks.noThrow("the attachment fields survive a re-encode") {
            val encoded = JSONValue.encode(agent)
            if (encoded["attach"]?.stringValue != "leader" || encoded["shared_interrupt"]?.boolValue != true ||
                encoded["shared_settings"]?.boolValue != true || encoded["shared_attachments"]?.boolValue != false) {
                throw ProtocolFailure.Malformed("the leader fields changed")
            }
        }
        checks.assertAll()
    }

    @Test
    fun events() {
        val checks = CheckRunner("protocol")
        // Amendment A3: an inbound attachment carries a size, not bytes.
        val message = checkNotNull(FixtureSource.json("events/user_message.json").decode<SessionEvent>().userMessage)
        checks.equal(message.attachments.firstOrNull()?.size, 284_913, "an inbound attachment carries its size")
        checks.equal(message.attachments.firstOrNull()?.mime, "image/png", "an inbound attachment carries its type")
        checks.equal(message.source, EventSource.remote, "a remote message is attributed to the app")

        // Amendment A43: a held message says how many files it carries, and one that carries none
        // says nothing at all.
        fun pending(file: String): List<QueuedMessage>? =
            (FixtureSource.json(file).decode<SessionEvent>().body as? SessionEventBody.Queue)?.payload?.pending
        val held = checkNotNull(pending("events/queue.attachments.json"))
        checks.equal(held.map { it.attachments }, listOf(null, 2), "a queue entry counts its files")
        checks.equal(held.map { it.carriesFiles }, listOf(false, true), "so the one with files is removed and never edited")
        checks.equal(held.map { it.ts }, held.map { it.ts }.sorted(), "and the queue is in ts order")
        checks.expect(pending("events/queue.json")?.all { it.attachments == null } == true,
                      "an entry without files carries no attachments field")

        // Amendment A1: the tool category rides in `tool_kind`.
        for ((file, kind) in listOf("events/tool_call.shell.json" to ToolKind.shell,
                                    "events/tool_call.read.json" to ToolKind.read,
                                    "events/tool_call.edit.json" to ToolKind.edit,
                                    "events/tool_call.write.json" to ToolKind.write,
                                    "events/tool_call.search.json" to ToolKind.search,
                                    "events/tool_call.web.json" to ToolKind.web,
                                    "events/tool_call.mcp.json" to ToolKind.mcp,
                                    "events/tool_call.subagent.json" to ToolKind.subagent,
                                    "events/tool_call.todo.json" to ToolKind.todo,
                                    "events/tool_call.other.json" to ToolKind.other)) {
            val event = FixtureSource.json(file).decode<SessionEvent>()
            checks.equal(event.kind, SessionEvent.toolCallKind, "$file is a tool_call event")
            checks.equal(event.toolCall?.kind, kind, "$file reports tool_kind $kind")
        }

        val call = checkNotNull(FixtureSource.json("events/tool_call.edit.json").decode<SessionEvent>().toolCall)
        checks.equal(call.diff?.additions, 4, "a diff carries its addition count")
        checks.equal(call.diff?.deletions, 2, "a diff carries its deletion count")
        checks.expect(call.diff?.patch?.contains("@@") == true, "a diff carries a unified patch")
        checks.expect(!call.isTruncated, "an untruncated tool call says so")

        val approval = checkNotNull(FixtureSource.json("events/approval.pending.json").decode<SessionEvent>().approval)
        checks.equal(approval.kind, ToolKind.edit, "an approval reports tool_kind")
        checks.equal(approval.primaryOption?.style, OptionStyle.primary, "an approval offers a primary option")
        checks.equal(approval.dangerOption?.style, OptionStyle.danger, "an approval offers a danger option")
        checks.expect(approval.status.isActionable, "a pending approval is actionable")
        checks.expect(approval.options.size >= 2, "every option the device sent is kept")

        val resolved = checkNotNull(FixtureSource.json("events/approval.resolved.json").decode<SessionEvent>().approval)
        checks.expect(!resolved.status.isActionable, "a resolved approval stops being actionable")
        checks.expect(resolved.decision != null, "a resolved approval records the decision")

        val question = checkNotNull(FixtureSource.json("events/question.pending.json").decode<SessionEvent>().question)
        checks.expect(question.questions.isNotEmpty(), "a question carries at least one prompt")
        checks.expect(question.status.isActionable, "a pending question is actionable")

        val answered = checkNotNull(FixtureSource.json("events/question.resolved.json").decode<SessionEvent>().question)
        checks.expect(answered.answers?.isEmpty() == false, "a resolved question carries answers")
        checks.expect(answered.by == null, "a question answered here names no other source")

        // Amendment A20: the person at the terminal answered their own dialog first, so the card
        // here says where the answer came from.
        val terminalEvent = FixtureSource.json("events/question.resolved.terminal.json").decode<SessionEvent>()
        val terminal = checkNotNull(terminalEvent.question)
        checks.equal(terminal.status, RequestStatus.resolved, "a question answered in the terminal resolves")
        checks.equal(terminal.by, EventSource.terminal, "and is attributed to the terminal")
        checks.expect(terminal.answers?.isEmpty() == false, "with the answers it was given there")
        checks.noThrow("the attribution survives a re-encode") {
            if (JSONValue.encode(terminalEvent)["by"]?.stringValue != "terminal") throw ProtocolFailure.Malformed("question.by was lost")
        }

        val delta = FixtureSource.json("events/assistant_text.delta.json").decode<SessionEvent>()
        checks.expect(delta.streamText?.done == false, "a delta event is not done")
        checks.expect(delta.streamText?.delta != null, "a delta event carries a delta")
        checks.expect(delta.isStreaming, "a delta event is still streaming")

        val done = FixtureSource.json("events/assistant_text.done.json").decode<SessionEvent>()
        checks.expect(done.streamText?.done == true, "a final event is done")
        checks.expect(done.streamText?.delta == null, "a final event carries no delta")

        // An unknown kind is kept whole rather than dropped.
        val unknown = runCatching {
            JSONValue.parse("""{"seq": 5, "ts": 1, "kind": "screenshot", "block_id": "s1", "url": "x"}""".encodeToByteArray())
                .decode<SessionEvent>()
        }.getOrNull()?.body as? SessionEventBody.Unknown
        if (unknown != null) {
            checks.equal(unknown.kind, "screenshot", "an unknown kind is preserved")
            checks.equal(unknown.raw["url"]?.stringValue, "x", "an unknown payload is preserved")
        } else {
            checks.expect(false, "an unknown event kind decodes to Unknown")
        }
        checks.noThrow("an unknown field on a known object is ignored") {
            jsonObjectOf("session_id" to "s", "device_id" to "d", "future_field" to 7).decode<Session>()
        }
        val state = runCatching { JSONValue.parse("\"hibernating\"".encodeToByteArray()).decode<SessionState>() }.getOrNull()
        checks.equal(state?.rawValue, "hibernating", "an unknown session state decodes rather than throwing")
        checks.assertAll()
    }
}
