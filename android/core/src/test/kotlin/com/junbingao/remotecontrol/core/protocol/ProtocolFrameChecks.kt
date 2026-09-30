package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.TimelineDetail
import com.junbingao.remotecontrol.core.transport.APNSRegistration
import com.junbingao.remotecontrol.core.transport.DeviceListResponse
import com.junbingao.remotecontrol.core.transport.DeviceResponse
import com.junbingao.remotecontrol.core.transport.GatewayConfig
import com.junbingao.remotecontrol.core.transport.LoginResponse
import com.junbingao.remotecontrol.core.transport.PairingClaim
import com.junbingao.remotecontrol.core.transport.PairingGrant
import com.junbingao.remotecontrol.core.transport.PolishStrength
import com.junbingao.remotecontrol.core.transport.PushRoute
import com.junbingao.remotecontrol.core.transport.SessionInfoResponse
import com.junbingao.remotecontrol.core.transport.SessionLink
import com.junbingao.remotecontrol.core.transport.SessionListResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URI
import java.time.Instant
import kotlin.test.Test

/**
 * `ios/Verification/ProtocolChecks.swift`, the frames, the HTTP bodies, the commands, the
 * terminals and the usage limit. The lines of those checks that read a store's type —
 * `DeviceUpdate`, `PairingClaimLink`, `CommandSection` — are `core-state`'s to add here.
 */
class ProtocolFrameChecks {
    @Test
    fun frames() {
        val checks = CheckRunner("protocol")
        val event = AppFrame(json = FixtureSource.json("app/session.event.json")) as AppFrame.SessionEvent
        checks.expect(event.sessionID.isNotEmpty(), "a session.event frame names its session")
        checks.expect(event.event.seq > 0, "a session.event frame carries a seq")
        // Amendment A5: the device id may be present, and is never required.
        checks.expect(event.deviceID == null || event.deviceID.isNotEmpty(),
                      "a session.event device id is either absent or a real id")
        checks.noThrow("session.event without a device id still decodes") {
            val json = jsonObjectOf("type" to "session.event", "session_id" to "s",
                                    "event" to mapOf("seq" to 1, "ts" to 1, "kind" to "notice", "level" to "info", "text" to "x"))
            val frame = AppFrame(json = json) as AppFrame.SessionEvent
            if (frame.deviceID != null) throw ProtocolFailure.Malformed("session.event device id")
        }
        checks.noThrow("session.removed without a device id still decodes") {
            val frame = AppFrame(json = jsonObjectOf("type" to "session.removed", "session_id" to "s")) as AppFrame.SessionRemoved
            if (frame.sessionID != "s" || frame.deviceID != null) throw ProtocolFailure.Malformed("session.removed device id")
        }
        val progress = AppFrame(json = FixtureSource.json("app/pairing.progress.json")) as? AppFrame.PairingProgress
        checks.expect(progress?.progress?.code?.isNotEmpty() == true, "pairing progress names the code")
        checks.expect(AppFrame(json = FixtureSource.json("app/ping.json")) == AppFrame.Ping, "app/ping.json decodes as a ping")
        val failure = (AppFrame(json = FixtureSource.json("app/reply.error.json")) as? AppFrame.Reply)
            ?.result?.exceptionOrNull() as? GatewayErrorBody
        checks.expect(failure?.message?.isNotEmpty() == true, "an error reply carries a message")
        checks.expect(failure?.code?.rawValue?.isNotEmpty() == true, "an error reply carries a code")
        val removed = AppFrame(json = FixtureSource.json("app/device.removed.json")) as? AppFrame.DeviceRemoved
        checks.expect(removed?.deviceID?.isNotEmpty() == true, "device.removed names the device")

        // Typed reply results.
        checks.noThrow("a subscribe reply decodes") {
            val subscribe = result("replay/subscribe.reply.json").decode<SubscribeResult>()
            if (subscribe.session.sessionID.isEmpty()) throw ProtocolFailure.Malformed("subscribe result")
        }
        // Amendment A6: the queue snapshot on a subscribe reply is optional.
        checks.noThrow("a subscribe reply carries an optional queue") {
            val withQueue = jsonObjectOf(
                "session" to mapOf("session_id" to "s", "device_id" to "d"),
                "events" to emptyList<Any>(), "resync" to false,
                "queue" to mapOf("pending" to listOf(mapOf("id" to "q1", "text" to "later", "ts" to 1))),
            )
            if (withQueue.decode<SubscribeResult>().queue?.pending?.size != 1) throw ProtocolFailure.Malformed("subscribe queue")
            val withoutQueue = jsonObjectOf("session" to mapOf("session_id" to "s", "device_id" to "d"))
            if (withoutQueue.decode<SubscribeResult>().queue != null) throw ProtocolFailure.Malformed("subscribe queue absence")
        }
        checks.noThrow("a history reply decodes") {
            val page = result("history/page.json").decode<HistoryResult>()
            // Section 8: history never carries deltas, status, meta or queue.
            for (entry in page.events) {
                if (entry.streamText?.delta != null) throw ProtocolFailure.Malformed("history contains a delta")
                if (entry.kind in listOf(SessionEvent.statusKind, SessionEvent.metaKind, SessionEvent.queueKind)) {
                    throw ProtocolFailure.Malformed("history contains ${entry.kind}")
                }
            }
            var previous = 0
            for (entry in page.events) {
                if (entry.seq <= previous) throw ProtocolFailure.Malformed("history is not ascending")
                previous = entry.seq
            }
        }
        for (file in listOf("app/reply.session.create.json", "app/reply.session.set.json",
                            "app/reply.session.takeover.json", "app/reply.session.archive.json")) {
            checks.noThrow("$file decodes as a session result") {
                if (result(file).decode<SessionResult>().session.sessionID.isEmpty()) throw ProtocolFailure.Malformed(file)
            }
        }
        checks.noThrow("a send reply decodes") { result("app/reply.session.send.json").decode<SendResult>() }
        checks.noThrow("a block reply decodes") { result("app/reply.session.block.json").decode<BlockResult>() }
        checks.noThrow("a directory listing decodes") {
            val listing = result("app/reply.device.dirs.json").decode<DirectoryListing>()
            if (listing.path.isEmpty() || listing.entries.isEmpty()) throw ProtocolFailure.Malformed("directory listing")
        }
        // Amendment A37: a made folder answers with its own listing, which is empty and stands
        // under the directory it was made in.
        checks.noThrow("a made directory's listing decodes") {
            val listing = result("app/reply.device.mkdir.json").decode<DirectoryListing>()
            val parent = listing.parent
            if (listing.entries.isNotEmpty() || parent == null || listing.path != "$parent/${File(listing.path).name}") {
                throw ProtocolFailure.Malformed("made directory listing")
            }
        }
        checks.noThrow("a git status decodes") { result("app/reply.device.git.json").decode<GitStatus>() }
        checks.noThrow("an agents listing decodes") {
            if (result("app/reply.device.agents.json").decode<AgentsResult>().agents.isEmpty()) {
                throw ProtocolFailure.Malformed("agents")
            }
        }
        // Amendment A21: only the agent that offers a tier carries one, and the other reads as an
        // empty list rather than as a missing field.
        val agents = result("app/reply.device.agents.json").decode<AgentsResult>().agents
        checks.equal(agents.firstOrNull { it.agent == "codex" }?.speeds?.map { it.id }, listOf("priority"),
                     "Codex advertises its faster tier")
        checks.equal(agents.firstOrNull { it.agent == "codex" }?.speedLabel("priority"), "Fast",
                     "with the name the agent gave it")
        checks.equal(agents.firstOrNull { it.agent == "claude" }?.speeds?.isEmpty(), true,
                     "and an agent that has none lists none")
        checks.assertAll()
    }

    /**
     * Amendments A22 and A36: the build a gateway serves and the build a device runs, as the wire
     * carries them.
     */
    @Test
    fun deviceUpdates() {
        val checks = CheckRunner("protocol")
        val served = checkNotNull(FixtureSource.json("http/config.response.json").decode<GatewayConfig>().servedBuild) {
            "http/config.response.json names the served client build"
        }
        checks.equal(served.length, 64, "the served build is a SHA-256")
        val devices = FixtureSource.json("http/devices.list.response.json").decode<DeviceListResponse>().devices
        checks.equal(devices.size, 2, "the device list decodes both devices")
        checks.equal(devices[0].clientBuild, served, "the first device runs the served build")
        checks.equal(devices[0].updateState, DeviceUpdateState.idle, "and reports no update in flight")
        checks.expect(devices[0].updateMessage == null, "and carries no update message")

        val device = (AppFrame(json = FixtureSource.json("app/device.updated.json")) as AppFrame.DeviceUpdated).device
        checks.equal(device.clientBuild, served, "device.updated carries the build the device runs")
        checks.equal(FixtureSource.json("device/hello.json")["client_build"]?.stringValue, served,
                     "the device's own hello reports the same build")

        val request = FixtureSource.json("app/device.update.json")
        val built = GatewayRequest.updateDevice(deviceID = request["device_id"]?.stringValue ?: "",
                                                build = request["build"]?.stringValue ?: "")
        checks.equal(built.type, request["type"]?.stringValue, "device.update is built as the fixture")
        checks.equal(built.body["build"], request["build"], "with the build the gateway serves")
        checks.noThrow("a device's acceptance decodes") {
            val result = result("app/reply.device.update.json").decode<DeviceUpdateResult>()
            if (!result.accepted || result.from?.length != 64) throw ProtocolFailure.Malformed("device.update reply")
        }
        checks.expect(FixtureSource.json("device/update.failed.json")["message"]?.stringValue?.isNotEmpty() == true,
                      "update.failed says why the update did not complete")
        checks.assertAll()
    }

    /** Amendment A23: the code claiming a host's link returns. */
    @Test
    fun claimTokens() {
        val checks = CheckRunner("protocol")
        val request = FixtureSource.json("http/devices.pairing.request.response.json")
        checks.expect(request["claim_url"]?.stringValue != null && request["token"]?.stringValue != null,
                      "http/devices.pairing.request.response.json names the link and its token")
        checks.equal(FixtureSource.json("http/devices.pairing.request.status.response.json")["status"]?.stringValue, "claimed",
                     "a claimed poll carries the code")
        checks.noThrow("a claim response decodes") {
            val decoded = FixtureSource.json("http/devices.pairing.claim.response.json").decode<PairingClaim>()
            if (!decoded.code.startsWith("RC-") || decoded.expiresAt <= 0) throw ProtocolFailure.Malformed("pairing claim")
        }
        checks.assertAll()
    }

    @Test
    fun http() {
        val checks = CheckRunner("protocol")
        checks.noThrow("a login response decodes") {
            if (FixtureSource.json("http/login.response.json").decode<LoginResponse>().token.isEmpty()) {
                throw ProtocolFailure.Malformed("login")
            }
        }
        checks.noThrow("a config response decodes") {
            if (FixtureSource.json("http/config.response.json").decode<GatewayConfig>().publicOrigin.isEmpty()) {
                throw ProtocolFailure.Malformed("config")
            }
        }
        checks.noThrow("a session response decodes") { FixtureSource.json("http/auth.session.response.json").decode<SessionInfoResponse>() }
        checks.noThrow("a device list decodes") { FixtureSource.json("http/devices.list.response.json").decode<DeviceListResponse>() }
        checks.noThrow("a renamed device decodes") { FixtureSource.json("http/devices.patch.response.json").decode<DeviceResponse>() }
        checks.noThrow("a session list decodes") { FixtureSource.json("http/sessions.list.response.json").decode<SessionListResponse>() }
        checks.noThrow("a pairing grant decodes") {
            val grant = FixtureSource.json("http/devices.pairing.response.json").decode<PairingGrant>()
            if (grant.code !in grant.install.macos) throw ProtocolFailure.Malformed("the install command must carry the code")
        }
        checks.noThrow("a push payload decodes into a route") {
            val route = PushRoute(userInfo = FixtureSource.json("http/push.payload.json").toString().encodeToByteArray())
            if (route.deepLink == null || route.deviceName.isEmpty()) throw ProtocolFailure.Malformed("push route")
        }
        checks.noThrow("an APNs registration decodes") {
            val registration = FixtureSource.json("http/push.apns.register.request.json").decode<APNSRegistration>()
            if (registration.token.isEmpty() || registration.bundleID.isEmpty()) throw ProtocolFailure.Malformed("apns registration")
        }
        checks.throwsError("a push payload from a newer protocol is refused") {
            PushRoute(userInfo = """{"rc": {"v": 2, "device_id": "d", "session_id": "s"}}""".encodeToByteArray())
        }
        checks.expect(SessionLink(url = URI("remotecontrol://session?device=d&id=s"))?.sessionID == "s", "a deep link parses back")
        checks.expect(SessionLink(url = URI("https://example.com/session?device=d&id=s")) == null, "a web URL is not a session link")
        checks.assertAll()
    }

    /**
     * Amendment A27: the list a session offers, what running one looks like in the transcript,
     * and which agents take them at all.
     */
    @Test
    fun commands() {
        val checks = CheckRunner("protocol")
        val result = result("app/reply.session.commands.json").decode<CommandsResult>()
        checks.equal(result.commands.map { it.name },
                     listOf("compact", "review", "init", "status", "release-notes", "skill:pdf-tables"),
                     "the worked list decodes in the order the device sent it")
        checks.equal(result.commands.firstOrNull { it.name == "review" }?.argument, "instructions",
                     "a command that takes an argument names the placeholder")
        checks.expect(result.commands.firstOrNull { it.name == "compact" }?.takesArgument == false,
                      "and one that takes none has no placeholder to show")
        checks.equal(result.commands.firstOrNull()?.slash, "/compact", "a row is drawn with its slash")
        checks.equal(result.commands.firstOrNull { it.name == "review" }?.line(argument = "the retry logic"),
                     "/review the retry logic", "and echoed as the line the user typed")
        checks.equal(JSONValue.emptyObject.decode<CommandsResult>().commands.size, 0,
                     "a device with nothing to offer answers an empty list rather than a failure")

        // A command a terminal would have printed the answer to reads as a tool call titled with
        // the command itself.
        val call = checkNotNull(FixtureSource.json("events/tool_call.command.json").decode<SessionEvent>().toolCall)
        checks.equal(call.tool, "/usage", "the block is named after the command")
        checks.equal(call.title, "/usage", "and titled with it")
        checks.equal(call.kind, ToolKind.other, "information is not a shell call or an edit")
        checks.equal(call.status, ToolStatus.succeeded, "and arrives finished")

        // The capability, from the protocol's own worked examples. Amendment A40 put it on Claude
        // too, once the shim gives the device a terminal it can type `/compact` into.
        for (file in listOf("agent.codex-daemon.json", "agent.grok.json", "agent.pi.json", "agent.claude-attach.json")) {
            val agent = FixtureSource.json("objects/$file").decode<AgentInfo>()
            checks.equal(agent.supports(AgentCapability.commands), true, "${agent.agent} takes commands from an app")
        }
        checks.assertAll()
    }

    /** Amendment A38: the five requests, the two replies, the two frames and the one field on `Device` that make a terminal. */
    @Test
    fun terminals() {
        val checks = CheckRunner("protocol")
        val output = (AppFrame(json = FixtureSource.json("app/terminal.output.json")) as AppFrame.TerminalOutput).output
        checks.expect(output.terminalID.isNotEmpty(), "terminal.output names its terminal")
        checks.expect(output.deviceID.isNotEmpty(), "and the machine it came from")
        checks.equal(output.seq, 1, "and starts its numbering at one")
        checks.expect(output.bytes?.isNotEmpty() == true, "and its data decodes from base64")

        val exit = (AppFrame(json = FixtureSource.json("app/terminal.exited.json")) as AppFrame.TerminalExited).exited
        checks.expect(exit.terminalID.isNotEmpty(), "terminal.exited names its terminal")
        checks.equal(exit.code, 0, "and carries the code the shell ended with")
        // The code is nullable on the wire: a device that could not read one says so rather than
        // inventing a zero, which means "it succeeded".
        checks.noThrow("an exit with no code decodes as no code") {
            val json = jsonObjectOf("type" to "terminal.exited", "terminal_id" to "t", "device_id" to "d", "code" to null)
            if ((AppFrame(json = json) as AppFrame.TerminalExited).exited.code != null) {
                throw ProtocolFailure.Malformed("terminal.exited code")
            }
        }
        checks.noThrow("an open reply names the terminal") {
            if (result("app/reply.terminal.open.json").decode<TerminalOpenResult>().terminalID.isEmpty()) {
                throw ProtocolFailure.Malformed("reply.terminal.open")
            }
        }
        checks.noThrow("an attach reply carries the size and the scrollback") {
            val attach = result("app/reply.terminal.attach.json").decode<TerminalAttachResult>()
            if (attach.cols <= 0 || attach.rows <= 0 || attach.scrollbackBytes?.isNotEmpty() != true) {
                throw ProtocolFailure.Malformed("reply.terminal.attach")
            }
        }

        // `Device.terminal` is optional on the wire; absent is how a client older than the
        // amendment answers, and it reads the same as false.
        checks.noThrow("a device says whether it offers a terminal, or says nothing") {
            val base = mapOf("device_id" to "d", "name" to "n", "platform" to "macos", "hostname" to "h", "arch" to "arm64",
                             "client_version" to "1", "online" to true, "last_seen" to 1, "created_at" to 1,
                             "latency_ms" to 1, "agents" to emptyList<Any>())
            if (!jsonOf(base + ("terminal" to true)).decode<Device>().offersTerminal) {
                throw ProtocolFailure.Malformed("device terminal true")
            }
            if (jsonOf(base + ("terminal" to false)).decode<Device>().terminal != false) {
                throw ProtocolFailure.Malformed("device terminal false")
            }
            val silent = jsonOf(base).decode<Device>()
            if (silent.terminal != null || silent.offersTerminal) throw ProtocolFailure.Malformed("device terminal absent")
        }

        // Protocol 6.3 bounds, applied here so a rotation into an odd size is clamped rather than refused.
        checks.equal(TerminalLimits.cols(0), 1, "a terminal is at least one column wide")
        checks.equal(TerminalLimits.cols(9_000), 500, "and at most five hundred")
        checks.equal(TerminalLimits.rows(0), 1, "at least one row tall")
        checks.equal(TerminalLimits.rows(9_000), 200, "and at most two hundred")
        checks.assertAll()
    }

    /** Amendment A35: a session the usage limit stopped, the resume it is waiting on, and every fixture that carries one. */
    @Test
    fun usageLimit() {
        val checks = CheckRunner("protocol")
        val pending = FixtureSource.json("objects/session.resume-pending.json").decode<Session>()
        checks.equal(pending.resume?.at, 1_788_966_060_000, "a pending resume carries its time")
        checks.equal(pending.resume?.estimated, false, "and says the vendor named the time")
        checks.equal(pending.resume?.attempts, 0, "and that no resume has run into the limit yet")
        checks.equal(pending.resume?.windowMinutes, 300, "and which window was hit")
        checks.equal(pending.control, SessionControl.shared, "on the one kind of session that can be dropped")
        // A session with no `resume` key at all is a session with no resume.
        checks.expect(FixtureSource.json("objects/session.shared-idle.json").decode<Session>().resume == null,
                      "a session without the field has no resume pending")

        val limit = (FixtureSource.json("events/turn_completed.limit.json").decode<SessionEvent>().body
            as SessionEventBody.TurnCompleted).payload
        checks.equal(limit.stopReason, StopReason.error, "a limit stop is an error stop")
        checks.equal(limit.limit?.windowMinutes, 300, "and names the window that was hit")
        checks.equal(limit.limit?.resetsAt, 1_788_966_000_000, "and when it resets")
        // A turn that ended for any other reason carries no limit at all.
        val ordinary = (FixtureSource.json("events/turn_completed.json").decode<SessionEvent>().body
            as SessionEventBody.TurnCompleted).payload
        checks.expect(ordinary.limit == null, "an ordinary turn carries no limit")

        val resume = checkNotNull(FixtureSource.json("events/resume.json").decode<SessionEvent>().resume)
        checks.equal(resume.status, ResumeStatus.scheduled, "a resume event says what the device did")
        checks.equal(resume.at, 1_788_966_060_000, "and when the prompt will go")
        checks.expect(resume.status.isDrawn, "and a scheduled resume is a row")
        val dropped = checkNotNull(FixtureSource.json("events/resume.dropped.json").decode<SessionEvent>().resume)
        checks.equal(dropped.status, ResumeStatus.dropped, "a dropped resume says so")
        checks.expect(dropped.reason?.isNotEmpty() == true, "in the device's own words")
        checks.expect(!ResumeStatus.fired.isDrawn, "the moment of resuming is not a row of its own")

        val message = checkNotNull(FixtureSource.json("events/user_message.resume.json").decode<SessionEvent>().userMessage)
        checks.equal(message.source, EventSource.resume, "the prompt is the one message the device writes")
        checks.expect(!message.source.isElsewhere, "and it is the person's own, so nothing reads it as somebody else's")
        val started = (FixtureSource.json("events/turn_started.resume.json").decode<SessionEvent>().body
            as SessionEventBody.TurnStarted).payload
        checks.equal(started.trigger, EventSource.resume, "the turn it starts says a resume started it")
        checks.expect(!started.trigger.isElsewhere, "which the status line reads as a remote turn")

        preferences(checks)
        requestsAndBounds(checks)
        checks.assertAll()
    }

    private fun preferences(checks: CheckRunner) {
        val response = FixtureSource.json("http/preferences.response.json").decode<PreferencesResponse>()
        checks.expect(response.preferences.resumeAfterLimit, "the account's switch decodes")
        // Amendment A41: the Settings screen's own preferences ride in the same object, and a full
        // one carries all six.
        val preferences = response.preferences
        checks.equal(preferences.language, InterfaceLanguage.zhHans,
                     "with the account's interface language")
        checks.equal(preferences.sttLanguage, "zh", "the language its phone's recogniser listens for (A44)")
        checks.equal(preferences.polishEnabled, true, "whether dictation is polished")
        checks.equal(preferences.polishModel, "gpt-5.4-mini", "by which model")
        checks.equal(preferences.polishStrength, PolishStrength.moderate,
                     "how far it may go")
        checks.equal(preferences.timelineDetail, TimelineDetail.detailed,
                     "and how much of a transcript is drawn")

        val route = FixtureSource.json("http/push.payload.limit.json")["rc"]?.decode<PushRoute>()
        checks.equal(route?.kind, PushKind.limitReached, "the pause push carries its own kind")
        checks.expect(route?.title?.contains("paused by the usage limit") == true, "and a title that names no time")

        val updated = (AppFrame(json = FixtureSource.json("app/preferences.updated.json")) as AppFrame.PreferencesUpdated).preferences
        checks.expect(updated.resumeAfterLimit, "preferences.updated carries the new value")
        checks.equal(updated.polishEnabled, true, "and every Settings preference beside it (A41)")
        checks.equal(updated.timelineDetail, TimelineDetail.detailed, "as one whole object")

        val device = (FixtureSource.json("device/preferences.json")["preferences"] ?: JSONValue.emptyObject).decode<Preferences>()
        checks.expect(device.resumeAfterLimit, "and so does the device's own frame")
        checks.equal(device.language, InterfaceLanguage.zhHans,
                     "with the same Settings fields on it")

        val hello = (AppFrame(json = FixtureSource.json("app/hello.json")) as AppFrame.Hello).hello
        checks.expect(hello.preferences != null, "hello carries the account's preferences")
        checks.equal(hello.preferences?.resumeAfterLimit, false, "and the switch is off until the person turns it on")
        // A41: every field but the switch is optional, so a hello that names three of them says
        // nothing about the other three, and those are the ones the app offers its own values for.
        checks.equal(hello.preferences?.language, InterfaceLanguage.en,
                     "a partial object decodes what it has")
        checks.equal(hello.preferences?.polishEnabled, false, "field by field")
        checks.equal(hello.preferences?.timelineDetail, TimelineDetail.simple,
                     "as far as it goes")
        checks.expect(hello.preferences?.sttLanguage == null, "and what it leaves out is unset rather than defaulted")
        checks.expect(hello.preferences?.polishModel == null, "on every absent field")
        checks.expect(hello.preferences?.polishStrength == null, "including the enums")

        // A41: an unknown word in an enum field reads as unset. The gateway validates every write,
        // so a word this build does not know is one a later build added, and the rest of the
        // account's object still stands.
        val members = FixtureSource.json("http/preferences.response.json")["preferences"]?.objectValue.orEmpty()
        val unknown = runCatching { JsonObject(members + ("language" to jsonOf("fr"))).decode<Preferences>() }.getOrNull()
        if (unknown != null) {
            checks.expect(unknown.language == null, "an interface language this build does not know reads as unset")
            checks.expect(unknown.resumeAfterLimit, "and the rest of the object is still the account's")
        } else {
            checks.expect(false, "a preferences object with an unknown word still decodes")
        }
        // The write is the fields it names and no others, under the wire's own spelling: a patch
        // that carried a field nobody touched would undo somebody else's change on its way through
        // the gateway.
        val body = JSONValue.encode(PreferencePatch(polishEnabled = true,
                                                    polishStrength = PolishStrength.strong))
            .objectValue.orEmpty()
        checks.equal(body.size, 2, "a patch carries only the fields it was given")
        checks.equal(body["polish_enabled"], jsonOf(true), "under the wire's names")
        checks.equal(body["polish_strength"], jsonOf("strong"), "with the wire's words")
        checks.expect(PreferencePatch().isEmpty, "a patch that changes nothing is not a request")
        checks.expect(!PreferencePatch(timelineDetail = TimelineDetail.simple).isEmpty,
                      "and one that sets a field to its default is")
        // A gateway older than the amendment sends none, which is what the app shows the switch
        // disabled for.
        val older = JsonObject(FixtureSource.json("app/hello.json").objectValue.orEmpty() - "preferences")
        checks.expect((AppFrame(json = older) as AppFrame.Hello).hello.preferences == null,
                      "and a gateway that sends none offers none")
    }

    private fun requestsAndBounds(checks: CheckRunner) {
        val set = FixtureSource.json("app/session.resume_set.json")
        val at = checkNotNull(set["at"]?.longValue)
        val built = GatewayRequest.resumeSet(sessionID = set["session_id"]?.stringValue ?: "", at = Instant.ofEpochMilli(at))
        checks.equal(built.type, "session.resume_set", "session.resume_set is built as the fixture")
        checks.equal(built.body["at"], jsonOf(at), "with the time in milliseconds")
        checks.equal(built.body["session_id"], set["session_id"], "and the session it is for")
        val cancel = FixtureSource.json("app/session.resume_cancel.json")
        val cancelled = GatewayRequest.resumeCancel(sessionID = cancel["session_id"]?.stringValue ?: "")
        checks.equal(cancelled.type, "session.resume_cancel", "and so is session.resume_cancel")
        checks.equal(cancelled.body["session_id"], cancel["session_id"], "for the same session")
        checks.noThrow("the reply carries the session with its resume") {
            val session = result("app/reply.session.resume_set.json").decode<SessionResult>().session
            if (session.resume?.at != 1_788_967_860_000) throw ProtocolFailure.Malformed("reply.session.resume_set")
        }

        // The bounds the device enforces, checked here so a time it would refuse never leaves the picker.
        val now = Instant.ofEpochSecond(1_788_966_000)
        checks.expect(!ResumeBounds.allows(now.plusSeconds(30), now = now), "a resume less than a minute ahead is refused")
        checks.expect(ResumeBounds.allows(now.plusSeconds(61), now = now), "a minute and a second ahead is allowed")
        checks.expect(ResumeBounds.allows(now.plusSeconds(8 * 86_400), now = now), "and so is eight days exactly")
        checks.expect(!ResumeBounds.allows(now.plusSeconds(8 * 86_400 + 60), now = now), "further out than eight days is refused")
    }

    private fun result(file: String): JsonElement = checkNotNull(FixtureSource.json(file)["result"]) { "$file has a result" }
}
