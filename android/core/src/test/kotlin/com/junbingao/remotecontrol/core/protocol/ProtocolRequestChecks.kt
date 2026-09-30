package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.FixtureSource
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.util.Base64
import kotlin.test.Test

/**
 * `ios/Verification/ProtocolChecks.swift`, the second half: the request builders must produce the
 * exact frames in `fixtures/app`, `first_seq` survives a re-encode, and a tool is classified from
 * its name when the wire says nothing.
 */
class ProtocolRequestChecks {
    private fun compare(built: GatewayRequest, file: String, checks: CheckRunner, ignoring: Set<String> = emptySet()) {
        val expected = FixtureSource.json(file).objectValue
        if (expected == null) {
            checks.expect(false, "$file exists")
            return
        }
        val actual = built.json
        val skip = ignoring + "id"
        for ((key, value) in expected) {
            if (key !in skip) checks.equal(actual[key], value, "$file field $key")
        }
        for (key in actual.keys) {
            if (key !in skip && expected[key] == null) checks.expect(false, "$file has no field $key, but the app sends one")
        }
        if (expected["id"] != null) checks.expect(actual["id"]?.stringValue?.isNotEmpty() == true, "$file carries a request id")
    }

    private fun fixture(file: String): JsonObject = checkNotNull(FixtureSource.json(file).objectValue) { "$file exists" }

    @Test
    fun requests() {
        val checks = CheckRunner("protocol")
        val send = fixture("app/session.send.json")
        val attachment = send.array("attachments").firstOrNull()?.objectValue
        val bytes = runCatching { Base64.getDecoder().decode(attachment?.string("data_base64") ?: "") }.getOrDefault(ByteArray(0))
        checks.noThrow("session.send matches the fixture") {
            val request = GatewayRequest.send(
                sessionID = send.string("session_id") ?: "", text = send.string("text") ?: "",
                attachments = listOf(OutboundAttachment(name = attachment?.string("name") ?: "",
                                                        mime = attachment?.string("mime") ?: "", data = bytes)),
                mode = SendMode(send.string("mode") ?: "auto"))
            compare(request, "app/session.send.json", checks)
        }
        checks.equal(bytes.isEmpty(), false, "the fixture attachment decodes from base64")

        val subscribe = fixture("app/session.subscribe.json")
        compare(GatewayRequest.subscribe(sessionID = subscribe.string("session_id") ?: "", sinceSeq = subscribe.int("since_seq")),
                "app/session.subscribe.json", checks)
        val create = fixture("app/session.create.json")
        compare(GatewayRequest.createSession(
            deviceID = create.string("device_id") ?: "", agent = create.string("agent") ?: "",
            cwd = create.string("cwd") ?: "", model = create.string("model"),
            permissionMode = create.string("permission_mode"), effort = create.string("effort"),
            speed = create["speed"]?.let { SpeedChange(id = it.stringValue) },
            worktree = create.bool("worktree"), firstMessage = create.string("first_message"),
            title = create.string("title")), "app/session.create.json", checks)
        val approve = fixture("app/session.approve.json")
        compare(GatewayRequest.approve(sessionID = approve.string("session_id") ?: "",
                                       requestID = approve.string("request_id") ?: "",
                                       optionID = approve.string("option_id") ?: "",
                                       message = approve.string("message")), "app/session.approve.json", checks)
        val answer = fixture("app/session.answer.json")
        checks.noThrow("session.answer matches the fixture") {
            val answers = (answer["answers"] ?: JSONValue.emptyObject).decode<Map<String, QuestionAnswer>>()
            compare(GatewayRequest.answer(sessionID = answer.string("session_id") ?: "",
                                          requestID = answer.string("request_id") ?: "", answers = answers),
                    "app/session.answer.json", checks)
        }
        val set = fixture("app/session.set.json")
        compare(GatewayRequest.set(sessionID = set.string("session_id") ?: "", model = set.string("model"),
                                   permissionMode = set.string("permission_mode"), effort = set.string("effort"),
                                   speed = set["speed"]?.let { SpeedChange(id = it.stringValue) },
                                   title = set.string("title")), "app/session.set.json", checks)
        // Amendment A21: going back to the standard speed is `speed: null`, and saying nothing
        // about the tier is the key not being there at all.
        checks.equal(GatewayRequest.set(sessionID = "s", speed = SpeedChange.Standard).json["speed"], JsonNull,
                     "session.set puts a session back to the standard speed with a null")
        checks.equal(GatewayRequest.set(sessionID = "s").json["speed"], null, "and leaves the tier alone by not naming it")
        // 5.11 the same way round: a `meta` that names the key with a null put the session back to
        // the standard speed, and one that omits it said nothing about the speed at all.
        checks.noThrow("a meta tells a cleared tier from an unmentioned one") {
            val cleared = jsonObjectOf("seq" to 1, "ts" to 1, "kind" to "meta", "speed" to null)
            if (cleared.decode<SessionEvent>().meta?.speed != SpeedChange.Standard) throw ProtocolFailure.Malformed("meta speed null")
            val raised = jsonObjectOf("seq" to 2, "ts" to 2, "kind" to "meta", "speed" to "priority")
            if (raised.decode<SessionEvent>().meta?.speed != SpeedChange.Tier("priority")) {
                throw ProtocolFailure.Malformed("meta speed tier")
            }
            val silent = jsonObjectOf("seq" to 3, "ts" to 3, "kind" to "meta", "model" to "gpt-5.4-codex")
            if (silent.decode<SessionEvent>().meta?.speed != null) throw ProtocolFailure.Malformed("meta speed absent")
        }
        val history = fixture("app/session.history.json")
        compare(GatewayRequest.history(sessionID = history.string("session_id") ?: "", beforeSeq = history.int("before_seq"),
                                       limit = history.int("limit") ?: RequestLimits.historyPageSize),
                "app/session.history.json", checks)
        val block = fixture("app/session.block.json")
        compare(GatewayRequest.block(sessionID = block.string("session_id") ?: "", blockID = block.string("block_id") ?: ""),
                "app/session.block.json", checks)
        compare(GatewayRequest.commands(sessionID = fixture("app/session.commands.json").string("session_id") ?: ""),
                "app/session.commands.json", checks)
        val run = fixture("app/session.command.json")
        compare(GatewayRequest.command(sessionID = run.string("session_id") ?: "", name = run.string("name") ?: "",
                                       argument = run.string("argument")), "app/session.command.json", checks)
        // Amendment A27: a command that takes nothing sends no argument at all, rather than an empty
        // one the device would have to read as a value.
        checks.equal(GatewayRequest.command(sessionID = "s", name = "compact").json["argument"], null,
                     "session.command omits an argument it was not given")
        val remove = fixture("app/session.queue_remove.json")
        compare(GatewayRequest.queueRemove(sessionID = remove.string("session_id") ?: "",
                                           queuedID = remove.string("queued_id") ?: ""), "app/session.queue_remove.json", checks)
        // Amendment A43: an edited queued message goes back under the `ts` its entry had, and no
        // other message carries one.
        val requeue = fixture("app/session.send.requeue.json")
        checks.noThrow("session.send matches the re-queue fixture") {
            compare(GatewayRequest.send(sessionID = requeue.string("session_id") ?: "", text = requeue.string("text") ?: "",
                                        mode = SendMode(requeue.string("mode") ?: "auto"), queueTs = requeue.long("queue_ts")),
                    "app/session.send.requeue.json", checks)
        }
        checks.noThrow("session.send names no queue_ts unless it is given one") {
            if (GatewayRequest.send(sessionID = "s", text = "t", mode = SendMode.queue).json["queue_ts"] != null) {
                throw ProtocolFailure.Malformed("queue_ts on an ordinary send")
            }
        }
        val archive = fixture("app/session.archive.json")
        compare(GatewayRequest.archive(sessionID = archive.string("session_id") ?: "", archived = archive.bool("archived") ?: true),
                "app/session.archive.json", checks)
        for ((file, build) in listOf<Pair<String, (String) -> GatewayRequest>>(
            "app/session.stop.json" to { GatewayRequest.stop(sessionID = it) },
            "app/session.takeover.json" to { GatewayRequest.takeover(sessionID = it) },
            "app/session.delete.json" to { GatewayRequest.delete(sessionID = it) },
        )) {
            compare(build(fixture(file).string("session_id") ?: ""), file, checks)
        }
        devicesAndTerminals(checks)
        compare(GatewayRequest.pong(), "app/pong.json", checks)

        // Bounds are checked before a message can cost the user their draft.
        checks.throwsError("more than eight attachments is refused before sending") {
            val one = OutboundAttachment(name = "a", mime = "image/png", data = byteArrayOf(0))
            GatewayRequest.send(sessionID = "s", text = "x", attachments = List(9) { one })
        }
        checks.throwsError("an oversized attachment is refused before sending") {
            GatewayRequest.send(sessionID = "s", text = "x", attachments = listOf(
                OutboundAttachment(name = "big", mime = "image/png", data = ByteArray(RequestLimits.maxAttachmentBytes + 1))))
        }
        checks.equal(GatewayRequest.history(sessionID = "s", limit = 5_000).json["limit"]?.intValue,
                     RequestLimits.maxHistoryPageSize, "the history limit is clamped")
        checks.noThrow("a retry keeps the original request id") {
            val first = GatewayRequest.send(id = "fixed", sessionID = "s", text = "x")
            val retry = GatewayRequest.send(id = "fixed", sessionID = "s", text = "x")
            if (first.id != retry.id || first.json["id"]?.stringValue != "fixed") throw ProtocolFailure.Malformed("retry id")
        }
        checks.expect(GatewayRequest.pong().json["id"] == null, "pong carries no request id")
        checks.expect(GatewayRequest.unsubscribe(sessionID = "s").json["id"] == null, "unsubscribe carries no request id")
        checks.assertAll()
    }

    private fun devicesAndTerminals(checks: CheckRunner) {
        val dirs = fixture("app/device.dirs.json")
        compare(GatewayRequest.dirs(deviceID = dirs.string("device_id") ?: "", path = dirs.string("path")),
                "app/device.dirs.json", checks)
        // Amendment A37: the three fields the device needs to make one folder.
        val mkdir = fixture("app/device.mkdir.json")
        compare(GatewayRequest.mkdir(deviceID = mkdir.string("device_id") ?: "", path = mkdir.string("path") ?: "",
                                     name = mkdir.string("name") ?: ""), "app/device.mkdir.json", checks)
        // Amendment A38: the five requests a terminal is driven by.
        val open = fixture("app/terminal.open.json")
        compare(GatewayRequest.terminalOpen(deviceID = open.string("device_id") ?: "", cols = open.int("cols") ?: 0,
                                            rows = open.int("rows") ?: 0), "app/terminal.open.json", checks)
        val input = fixture("app/terminal.input.json")
        checks.noThrow("terminal.input matches the fixture") {
            val bytes = Base64.getDecoder().decode(input.string("data") ?: "")
            if (bytes.isEmpty()) throw ProtocolFailure.Malformed("terminal.input data")
            compare(GatewayRequest.terminalInput(deviceID = input.string("device_id") ?: "",
                                                 terminalID = input.string("terminal_id") ?: "", data = bytes),
                    "app/terminal.input.json", checks)
        }
        // 64 KiB decoded is the device's limit, and the app keeps it: a paste larger than one frame
        // is refused here rather than on the machine.
        checks.noThrow("an input larger than the frame is refused before it is sent") {
            try {
                GatewayRequest.terminalInput(deviceID = "d", terminalID = "t", data = ByteArray(TerminalLimits.maxInputBytes + 1))
                throw ProtocolFailure.Malformed("oversize input was built")
            } catch (_: TerminalInputError.TooLarge) {
                // The refusal the paste path reads.
            }
        }
        val resize = fixture("app/terminal.resize.json")
        compare(GatewayRequest.terminalResize(deviceID = resize.string("device_id") ?: "",
                                              terminalID = resize.string("terminal_id") ?: "",
                                              cols = resize.int("cols") ?: 0, rows = resize.int("rows") ?: 0),
                "app/terminal.resize.json", checks)
        for ((file, build) in listOf<Pair<String, (String, String) -> GatewayRequest>>(
            "app/terminal.attach.json" to { device, terminal -> GatewayRequest.terminalAttach(deviceID = device, terminalID = terminal) },
            "app/terminal.close.json" to { device, terminal -> GatewayRequest.terminalClose(deviceID = device, terminalID = terminal) },
        )) {
            val json = fixture(file)
            compare(build(json.string("device_id") ?: "", json.string("terminal_id") ?: ""), file, checks)
        }
        val git = fixture("app/device.git.json")
        compare(GatewayRequest.git(deviceID = git.string("device_id") ?: "", path = git.string("path") ?: ""),
                "app/device.git.json", checks)
        compare(GatewayRequest.agents(deviceID = fixture("app/device.agents.json").string("device_id") ?: ""),
                "app/device.agents.json", checks)
        compare(GatewayRequest.unsubscribe(sessionID = fixture("app/session.unsubscribe.json").string("session_id") ?: ""),
                "app/session.unsubscribe.json", checks)
    }

    /** Amendment A8: `first_seq` is optional, decoded, and survives a re-encode. */
    @Test
    fun firstSeq() {
        val checks = CheckRunner("protocol")
        var seen = 0
        for (file in FixtureSource.files(java.io.File(FixtureSource.fixtures, "events"))) {
            val json = runCatching { JSONValue.parse(file.readBytes()) }.getOrNull() ?: continue
            val declared = json["first_seq"]?.intValue ?: continue
            val event = runCatching { json.decode<SessionEvent>() }.getOrNull() ?: continue
            seen += 1
            val label = FixtureSource.label(file)
            checks.equal(event.firstSeq, declared, "$label decodes first_seq")
            checks.equal(event.orderSeq, declared, "$label orders by first_seq")
            checks.equal(JSONValue.encode(event)["first_seq"]?.intValue, declared, "$label keeps first_seq on re-encoding")
        }
        checks.expect(seen > 0, "at least one event fixture carries first_seq")
        checks.noThrow("an event without first_seq orders by its own seq") {
            val event = jsonObjectOf("seq" to 7, "ts" to 1, "kind" to "assistant_text", "block_id" to "a",
                                     "text" to "x", "done" to true).decode<SessionEvent>()
            if (event.firstSeq != null || event.orderSeq != 7) throw ProtocolFailure.Malformed("first_seq default")
        }
        checks.assertAll()
    }

    @Test
    fun toolKinds() {
        val checks = CheckRunner("protocol")
        checks.equal(ToolKind.derived(fromTool = "Bash"), ToolKind.shell, "Bash falls back to shell")
        checks.equal(ToolKind.derived(fromTool = "Edit"), ToolKind.edit, "Edit falls back to edit")
        checks.equal(ToolKind.derived(fromTool = "Task"), ToolKind.subagent, "Task falls back to sub-agent")
        checks.equal(ToolKind.derived(fromTool = "mcp__linear__issue"), ToolKind.mcp, "an MCP tool falls back to mcp")
        checks.equal(ToolKind.derived(fromTool = "Whatever"), ToolKind.other, "an unknown tool falls back to other")
        checks.assertAll()
    }
}
