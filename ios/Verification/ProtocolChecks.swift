import Foundation
import RCCore

/// Decodes every fixture in `protocol/fixtures` and pins down the decoding
/// rules the UI relies on. Decoding is the contract test for a client: the
/// schema itself is validated by `protocol/scripts/validate_fixtures.py`.
enum ProtocolChecks {
    static func run() -> CheckResult {
        let checks = CheckRunner(group: "protocol")
        let files = FixtureSource.files(in: FixtureSource.fixtures)
        checks.expect(!files.isEmpty, "protocol/fixtures contains JSON files")
        print("fixtures: \(files.count) under \(FixtureSource.fixtures.path)")

        for file in files { decode(file, checks: checks) }

        objects(checks: checks)
        sharedControl(checks: checks)
        codexDaemon(checks: checks)
        grokLeader(checks: checks)
        events(checks: checks)
        commands(checks: checks)
        frames(checks: checks)
        http(checks: checks)
        requests(checks: checks)
        firstSeq(checks: checks)
        toolKinds(checks: checks)
        usageLimit(checks: checks)
        return checks.result()
    }

    /// Every fixture must parse, and every fixture the app consumes must decode
    /// into a typed model and survive a re-encode without losing a field.
    private static func decode(_ file: URL, checks: CheckRunner) {
        let name = FixtureSource.label(file)
        guard let data = try? Data(contentsOf: file),
              let json = try? JSONDecoder().decode(JSONValue.self, from: data) else {
            checks.expect(false, "\(name) parses as JSON")
            return
        }
        checks.noThrow("\(name) survives a JSONValue round trip") {
            let again = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(json))
            guard again == json else { throw ProtocolFailure.malformed(name) }
        }
        if name.hasPrefix("events/") {
            checks.noThrow("\(name) round-trips as SessionEvent") {
                let event = try json.decode(SessionEvent.self)
                let encoded = try JSONValue.encode(event)
                let again = try encoded.decode(SessionEvent.self)
                guard again.seq == event.seq, again.kind == event.kind, again.body == event.body,
                      again.blockID == event.blockID, again.parentBlockID == event.parentBlockID else {
                    throw ProtocolFailure.malformed("\(name) changed on re-encoding")
                }
                for key in json.objectValue?.keys ?? [:].keys where encoded[key] == nil {
                    throw ProtocolFailure.malformed("\(name) lost field \(key)")
                }
            }
        }
        if name.hasPrefix("objects/") {
            checks.noThrow("\(name) decodes as the object it names") {
                if name.contains("/session.") { _ = try json.decode(Session.self) }
                if name.contains("/agent.") { _ = try json.decode(AgentInfo.self) }
            }
        }
        if name.hasPrefix("app/") {
            checks.noThrow("\(name) decodes as an app frame or request") {
                guard let type = json["type"]?.stringValue else {
                    throw ProtocolFailure.malformed("\(name) has no type")
                }
                // Frames the gateway sends decode into AppFrame; frames the app
                // sends are covered by the request-builder checks below.
                if Self.inboundTypes.contains(type) { _ = try AppFrame(json: json) }
            }
        }
    }

    private static let inboundTypes: Set<String> = [
        "hello", "device.updated", "device.removed", "session.updated", "session.removed",
        "session.event", "pairing.progress", "preferences.updated", "ping", "reply"
    ]

    /// Amendment A35: a session the usage limit stopped, the resume it is
    /// waiting on, and every fixture that carries one.
    private static func usageLimit(checks: CheckRunner) {
        if let json = FixtureSource.json("objects/session.resume-pending.json"),
           let session = try? json.decode(Session.self) {
            checks.equal(session.resume?.at, 1_788_966_060_000, "a pending resume carries its time")
            checks.equal(session.resume?.estimated, false, "and says the vendor named the time")
            checks.equal(session.resume?.attempts, 0, "and that no resume has run into the limit yet")
            checks.equal(session.resume?.windowMinutes, 300, "and which window was hit")
            checks.equal(session.control, .shared, "on the one kind of session that can be dropped")
        } else {
            checks.expect(false, "objects/session.resume-pending.json decodes as a Session")
        }
        // A session with no `resume` key at all is a session with no resume.
        if let json = FixtureSource.json("objects/session.shared-idle.json"),
           let session = try? json.decode(Session.self) {
            checks.expect(session.resume == nil, "a session without the field has no resume pending")
        }

        if let json = FixtureSource.json("events/turn_completed.limit.json"),
           let event = try? json.decode(SessionEvent.self),
           case .turnCompleted(let payload) = event.body {
            checks.equal(payload.stopReason, .error, "a limit stop is an error stop")
            checks.equal(payload.limit?.windowMinutes, 300, "and names the window that was hit")
            checks.equal(payload.limit?.resetsAt, 1_788_966_000_000, "and when it resets")
        } else {
            checks.expect(false, "events/turn_completed.limit.json decodes")
        }
        // A turn that ended for any other reason carries no limit at all.
        if let json = FixtureSource.json("events/turn_completed.json"),
           let event = try? json.decode(SessionEvent.self),
           case .turnCompleted(let payload) = event.body {
            checks.expect(payload.limit == nil, "an ordinary turn carries no limit")
        }

        if let json = FixtureSource.json("events/resume.json"),
           let event = try? json.decode(SessionEvent.self), let payload = event.resume {
            checks.equal(payload.status, .scheduled, "a resume event says what the device did")
            checks.equal(payload.at, 1_788_966_060_000, "and when the prompt will go")
            checks.expect(payload.status.isDrawn, "and a scheduled resume is a row")
        } else {
            checks.expect(false, "events/resume.json decodes as a resume event")
        }
        if let json = FixtureSource.json("events/resume.dropped.json"),
           let event = try? json.decode(SessionEvent.self), let payload = event.resume {
            checks.equal(payload.status, .dropped, "a dropped resume says so")
            checks.expect(payload.reason?.isEmpty == false, "in the device's own words")
        }
        checks.expect(!ResumeStatus.fired.isDrawn,
                      "the moment of resuming is not a row of its own")

        if let json = FixtureSource.json("events/user_message.resume.json"),
           let event = try? json.decode(SessionEvent.self), let message = event.userMessage {
            checks.equal(message.source, .resume, "the prompt is the one message the device writes")
            checks.expect(!message.source.isElsewhere,
                          "and it is the person's own, so nothing reads it as somebody else's")
        }
        if let json = FixtureSource.json("events/turn_started.resume.json"),
           let event = try? json.decode(SessionEvent.self),
           case .turnStarted(let payload) = event.body {
            checks.equal(payload.trigger, .resume, "the turn it starts says a resume started it")
            checks.expect(!payload.trigger.isElsewhere, "which the status line reads as a remote turn")
        }

        if let json = FixtureSource.json("http/preferences.response.json"),
           let response = try? json.decode(PreferencesResponse.self) {
            checks.expect(response.preferences.resumeAfterLimit, "the account's switch decodes")
        } else {
            checks.expect(false, "http/preferences.response.json decodes")
        }
        if let json = FixtureSource.json("http/push.payload.limit.json"),
           let route = try? (json["rc"] ?? .object([:])).decode(PushRoute.self) {
            checks.equal(route.kind, .limitReached, "the pause push carries its own kind")
            checks.expect(route.title.contains("paused by the usage limit"),
                          "and a title that names no time")
        }
        if let json = FixtureSource.json("app/preferences.updated.json"),
           case .preferencesUpdated(let preferences)? = try? AppFrame(json: json) {
            checks.expect(preferences.resumeAfterLimit, "preferences.updated carries the new value")
        } else {
            checks.expect(false, "app/preferences.updated.json decodes as an app frame")
        }
        if let json = FixtureSource.json("device/preferences.json"),
           let preferences = try? (json["preferences"] ?? .object([:])).decode(Preferences.self) {
            checks.expect(preferences.resumeAfterLimit, "and so does the device's own frame")
        }
        if let hello = FixtureSource.json("app/hello.json"), let frame = try? AppFrame(json: hello),
           case .hello(let payload) = frame {
            checks.expect(payload.preferences != nil, "hello carries the account's preferences")
            checks.equal(payload.preferences?.resumeAfterLimit, false,
                         "and the switch is off until the person turns it on")
        }
        // A gateway older than the amendment sends none, which is what the
        // app shows the switch disabled for.
        if let hello = FixtureSource.json("app/hello.json")?.objectValue {
            var older = hello
            older.removeValue(forKey: "preferences")
            if case .hello(let payload)? = try? AppFrame(json: .object(older)) {
                checks.expect(payload.preferences == nil, "and a gateway that sends none offers none")
            }
        }

        if let json = FixtureSource.json("app/session.resume_set.json"),
           let at = json["at"]?.intValue {
            let built = GatewayRequest.resumeSet(
                sessionID: json["session_id"]?.stringValue ?? "",
                at: Date(timeIntervalSince1970: Double(at) / 1000))
            checks.equal(built.type, "session.resume_set", "session.resume_set is built as the fixture")
            checks.equal(built.body["at"], .integer(Int64(at)), "with the time in milliseconds")
            checks.equal(built.body["session_id"], json["session_id"], "and the session it is for")
        }
        if let json = FixtureSource.json("app/session.resume_cancel.json") {
            let built = GatewayRequest.resumeCancel(sessionID: json["session_id"]?.stringValue ?? "")
            checks.equal(built.type, "session.resume_cancel", "and so is session.resume_cancel")
            checks.equal(built.body["session_id"], json["session_id"], "for the same session")
        }
        if let reply = FixtureSource.json("app/reply.session.resume_set.json"),
           let result = reply["result"] {
            checks.noThrow("the reply carries the session with its resume") {
                let session = try result.decode(SessionResult.self).session
                guard session.resume?.at == 1_788_967_860_000 else {
                    throw ProtocolFailure.malformed("reply.session.resume_set")
                }
            }
        }

        // The bounds the device enforces, checked here so a time it would
        // refuse never leaves the picker.
        let now = Date(timeIntervalSince1970: 1_788_966_000)
        checks.expect(!ResumeBounds.allows(now.addingTimeInterval(30), now: now),
                      "a resume less than a minute ahead is refused")
        checks.expect(ResumeBounds.allows(now.addingTimeInterval(61), now: now),
                      "a minute and a second ahead is allowed")
        checks.expect(ResumeBounds.allows(now.addingTimeInterval(8 * 86_400), now: now),
                      "and so is eight days exactly")
        checks.expect(!ResumeBounds.allows(now.addingTimeInterval(8 * 86_400 + 60), now: now),
                      "further out than eight days is refused")
    }

    private static func objects(checks: CheckRunner) {
        guard let hello = FixtureSource.json("app/hello.json"),
              let frame = try? AppFrame(json: hello), case .hello(let payload) = frame else {
            checks.expect(false, "app/hello.json decodes")
            return
        }
        checks.equal(payload.protocolVersion, RemoteProtocol.version, "hello carries protocol 1")
        checks.equal(payload.devices.count, 2, "hello carries both devices")
        checks.equal(payload.sessions.count, 2, "hello carries both sessions")
        checks.expect(payload.stt.enabled, "hello reports gateway transcription")

        guard let mac = payload.devices.first, let claude = mac.agent("claude") else {
            checks.expect(false, "the first device exposes Claude")
            return
        }
        checks.expect(claude.supports(.takeover), "Claude advertises takeover")
        checks.expect(!claude.supports(.steer), "Claude does not advertise steering")
        checks.expect(mac.agent("codex")?.supports(.steer) == true, "Codex advertises steering")
        checks.equal(claude.modelLabel("claude-sonnet-4-5"), "Sonnet 4.5", "a model id resolves to its label")
        checks.equal(claude.modelLabel("unknown-model"), "unknown-model", "an unknown model falls back to its id")
        checks.equal(claude.permissionModeLabel("acceptEdits"), "Auto-accept edits", "permission modes are labelled")
        checks.equal(mac.availableAgents.count, 2, "both agents are available")
        checks.expect(payload.devices[1].online == false, "the offline device is reported offline")
        checks.expect(payload.devices[1].latencyMS == nil, "a null latency decodes as nil")

        // Amendment A2: token totals are required, cost and context are not.
        let session = payload.sessions[0]
        checks.equal(session.usage?.totalTokens, 54_330, "usage totals decode")
        checks.equal(session.usage?.costUSD, 0.42, "a reported cost decodes")
        checks.expect(payload.sessions[1].usage?.costUSD == nil, "a missing cost decodes as nil")
        checks.equal(payload.sessions[1].usage?.totalTokens, 30_440, "totals decode without a cost")
        checks.equal(session.folderName, "gateway", "the folder name is derived from the working directory")

        guard let noCost = FixtureSource.json("events/turn_completed.no_cost.json"),
              let event = try? noCost.decode(SessionEvent.self),
              case .turnCompleted(let completed) = event.body else {
            checks.expect(false, "events/turn_completed.no_cost.json decodes")
            return
        }
        checks.expect(completed.usage?.costUSD == nil, "a turn without a cost decodes")
        checks.expect((completed.usage?.totalTokens ?? 0) > 0, "a turn without a cost still reports totals")
    }

    /// Amendment A10: `shared` sessions, the attachment fields on an agent and
    /// the delivery state of a message the device is holding.
    private static func sharedControl(checks: CheckRunner) {
        if let json = FixtureSource.json("objects/session.shared-idle.json"),
           let session = try? json.decode(Session.self) {
            checks.equal(session.control, .shared, "an attached session reports shared control")
            checks.expect(session.isAttached, "and the app reads it as attached")
            checks.expect(!session.isControlledByTerminal, "an attached session is not locked to the terminal")
            checks.equal(session.state, .idle, "an attached session idles rather than going read-only")
            checks.equal(session.origin, .terminal, "the CLI still started it")
        } else {
            checks.expect(false, "objects/session.shared-idle.json decodes as a session")
        }

        if let json = FixtureSource.json("objects/session.shared-running.json"),
           let session = try? json.decode(Session.self) {
            checks.expect(session.isAttached, "a running attached session is still attached")
            checks.expect(session.state.isWorking, "and reports a turn in progress")
            checks.equal(session.queued, 1, "a held message counts against the queue")
        } else {
            checks.expect(false, "objects/session.shared-running.json decodes as a session")
        }

        if let json = FixtureSource.json("objects/agent.claude-attach.json"),
           let agent = try? json.decode(AgentInfo.self) {
            checks.equal(agent.attach, .channel, "Claude attaches through a channel")
            checks.expect(agent.attachReady, "the device says the shim is installed")
            checks.expect(!agent.sharedInterrupt, "a channel cannot interrupt a running turn")
            checks.expect(!agent.sharedSettings, "nor retune the thread it relays into")
            checks.expect(!agent.sharedAttachments, "nor hand it bytes")
            checks.expect(agent.supports(.takeover), "takeover is unchanged by the attachment")
            checks.noThrow("the attachment fields survive a re-encode") {
                let again = try JSONValue.encode(agent).decode(AgentInfo.self)
                guard again.attach == agent.attach, again.attachReady == agent.attachReady,
                      again.sharedInterrupt == agent.sharedInterrupt,
                      again.sharedSettings == agent.sharedSettings,
                      again.sharedAttachments == agent.sharedAttachments else {
                    throw ProtocolFailure.malformed("attachment fields changed")
                }
            }
        } else {
            checks.expect(false, "objects/agent.claude-attach.json decodes as an agent")
        }

        // An agent that says nothing about attaching is not attachable.
        if let hello = FixtureSource.json("app/hello.json"), let frame = try? AppFrame(json: hello),
           case .hello(let payload) = frame, let claude = payload.devices.first?.agent("claude") {
            checks.expect(claude.attach == nil, "an agent without an attachment reports none")
            checks.expect(!claude.attachReady, "and is not ready to attach")
            checks.expect(!claude.sharedInterrupt, "and does not claim a shared interrupt")
            checks.expect(!claude.sharedSettings, "nor shared settings")
            checks.expect(!claude.sharedAttachments, "nor shared attachments")
        }

        for (file, expected) in [("events/user_message.delivered.json", MessageDelivery.delivered),
                                 ("events/user_message.absorbed.json", .absorbed)] {
            guard let json = FixtureSource.json(file), let event = try? json.decode(SessionEvent.self),
                  let message = event.userMessage else {
                checks.expect(false, "\(file) decodes as a user message")
                continue
            }
            checks.equal(message.delivery, expected, "\(file) carries its delivery state")
            checks.equal(message.source, .remote, "a message sent from an app stays remote")
            checks.noThrow("\(file) keeps its delivery on a re-encode") {
                guard try JSONValue.encode(event)["delivery"]?.stringValue == expected.rawValue else {
                    throw ProtocolFailure.malformed("\(file) lost its delivery")
                }
            }
        }

        // Amendment A19: a message the device is still holding is a queue entry
        // and no block at all, so `pending` is not a delivery state any more and
        // the block that does appear is placed after the turn it waited for.
        checks.expect(MessageDelivery(rawValue: "pending") != .delivered,
                      "a device that still says pending is not mistaken for a delivered message")
        if let json = FixtureSource.json("events/user_message.delivered.json"),
           let event = try? json.decode(SessionEvent.self) {
            checks.equal(event.orderSeq, event.firstSeq,
                         "an injected message is drawn where its block started, not where it landed")
        }

        // An ordinary prompt says nothing about delivery.
        if let json = FixtureSource.json("events/user_message.json"),
           let event = try? json.decode(SessionEvent.self) {
            checks.expect(event.userMessage?.delivery == nil, "a normal prompt has no delivery state")
        }

        if let json = FixtureSource.json("events/approval.shared-pending.json"),
           let event = try? json.decode(SessionEvent.self), let approval = event.approval {
            checks.equal(approval.options.map(\.id), ["allow", "deny"],
                         "a relayed request offers exactly allow and deny")
            checks.expect(approval.diff == nil, "the relay does not provide a diff")
            checks.equal(approval.input?["tool_name"]?.stringValue, "Bash",
                         "the relay passes the tool name through")
            checks.expect(approval.input?["input_preview"] != nil, "and a preview of the input")
        } else {
            checks.expect(false, "events/approval.shared-pending.json decodes")
        }

        if let json = FixtureSource.json("events/approval.shared-terminal.json"),
           let event = try? json.decode(SessionEvent.self), let approval = event.approval {
            checks.equal(approval.status, .resolved, "an approval answered in the terminal resolves")
            checks.equal(approval.decision?.by, .terminal, "and is attributed to the terminal")
        } else {
            checks.expect(false, "events/approval.shared-terminal.json decodes")
        }

        // A value from a newer device decodes without pretending to be known.
        let unknownControl = SessionControl(rawValue: "attached")
        checks.expect(unknownControl != .shared && unknownControl != .terminal,
                      "an unrecognised control is not mistaken for a known one")
        let unknownDelivery = MessageDelivery(rawValue: "queued")
        checks.expect(unknownDelivery != .delivered && unknownDelivery != .absorbed,
                      "an unrecognised delivery is not mistaken for a known one")
    }

    /// Amendment A11: Codex through the shared app-server daemon. The
    /// attachment carries the settings, the attachments and the interrupt, and
    /// a request answered by whoever else holds the thread comes back with an
    /// option id the block never offered.
    private static func codexDaemon(checks: CheckRunner) {
        if let json = FixtureSource.json("objects/agent.codex-daemon.json"),
           let agent = try? json.decode(AgentInfo.self) {
            checks.equal(agent.attach, .daemon, "Codex attaches through the app-server daemon")
            checks.expect(agent.attachReady, "the device handshook with the daemon socket")
            checks.expect(agent.sharedInterrupt, "the daemon relays an interrupt")
            checks.expect(agent.sharedSettings, "and the thread settings")
            checks.expect(agent.sharedAttachments, "and image inputs")
            checks.expect(!agent.supports(.takeover), "there is nothing to take over from")
            checks.expect(agent.supports(.effort), "effort is one of the settings it relays")
            checks.noThrow("the two booleans survive a re-encode") {
                let encoded = try JSONValue.encode(agent)
                guard encoded["shared_settings"]?.boolValue == true,
                      encoded["shared_attachments"]?.boolValue == true else {
                    throw ProtocolFailure.malformed("the shared booleans changed")
                }
            }
        } else {
            checks.expect(false, "objects/agent.codex-daemon.json decodes as an agent")
        }

        // Absent means false, so a device that never heard of A11 is safe.
        if let bare = try? JSONValue.object(["agent": "codex", "available": true])
            .decode(AgentInfo.self) {
            checks.expect(!bare.sharedSettings, "an agent that says nothing keeps its settings local")
            checks.expect(!bare.sharedAttachments, "and takes no attachments")
        }

        // A boolean field that is not a boolean is refused, not coerced.
        let broken = FixtureSource.invalid
            .appending(path: "objects.agent__shared_settings_not_boolean.json")
        if let data = try? Data(contentsOf: broken),
           let json = try? JSONDecoder().decode(JSONValue.self, from: data) {
            checks.expect((try? json.decode(AgentInfo.self)) == nil,
                          "a non-boolean shared_settings is refused rather than coerced")
        } else {
            checks.expect(false, "the negative shared_settings fixture is readable")
        }

        if let json = FixtureSource.json("objects/session.codex-shared-running.json"),
           let session = try? json.decode(Session.self) {
            checks.equal(session.agent, "codex", "the shared thread runs Codex")
            checks.expect(session.isAttached, "the device is attached to it")
            checks.equal(session.origin, .terminal, "the terminal started it")
            checks.expect(session.state.isWorking, "and a turn is running")
        } else {
            checks.expect(false, "objects/session.codex-shared-running.json decodes as a session")
        }

        if let json = FixtureSource.json("events/approval.codex-shared-pending.json"),
           let event = try? json.decode(SessionEvent.self), let approval = event.approval {
            checks.equal(approval.options.map(\.id),
                         ["allow", "allow_session", "allow_always", "deny"],
                         "the daemon's four decisions arrive as four options")
            checks.equal(approval.primaryOption?.id, "allow", "with one primary")
            checks.equal(approval.dangerOption?.id, "deny", "and one danger")
            checks.equal(approval.otherOptions.map(\.id), ["allow_session", "allow_always"],
                         "and the rest stacked between them")
            checks.equal(approval.input?["cwd"]?.stringValue, "/Users/me/dev/web",
                         "a command request carries its working directory")
            checks.expect(approval.input?["command_actions"] != nil, "and what the command does")
        } else {
            checks.expect(false, "events/approval.codex-shared-pending.json decodes")
        }

        if let json = FixtureSource.json("events/approval.codex-elsewhere.json"),
           let event = try? json.decode(SessionEvent.self), let approval = event.approval {
            checks.equal(approval.status, .resolved, "a request answered elsewhere resolves")
            checks.equal(approval.decision?.optionID, ApprovalPayload.elsewhereOptionID,
                         "with the reserved option id")
            checks.equal(approval.decision?.by, .terminal, "attributed to the terminal")
            checks.expect(approval.resolvedOptionLabel == nil,
                          "and nothing to name, so the card says only who answered")
        } else {
            checks.expect(false, "events/approval.codex-elsewhere.json decodes")
        }

        // An id the block never offered still names itself rather than blanking.
        let unknown = ApprovalPayload(
            requestID: "r", tool: "shell", kind: .shell, title: "t",
            options: [ApprovalOption(id: "allow", label: "Allow", style: .primary)],
            status: .resolved, decision: ApprovalDecision(optionID: "allow_next_week", by: .terminal))
        checks.equal(unknown.resolvedOptionLabel, "allow_next_week",
                     "an unrecognised option id renders verbatim")
    }

    /// Amendment A28: Grok Build through the leader its terminals join. The
    /// leader relays an interrupt and the session settings to every client of
    /// it, and its prompts take no images, so one of the five fields is false.
    private static func grokLeader(checks: CheckRunner) {
        guard let json = FixtureSource.json("objects/agent.grok.json"),
              let agent = try? json.decode(AgentInfo.self) else {
            checks.expect(false, "objects/agent.grok.json decodes as an agent")
            return
        }
        checks.equal(agent.attach, .leader, "Grok Build attaches through its leader")
        checks.equal(AgentAttach(rawValue: "leader"), .leader, "which the app reads by that name")
        checks.expect(agent.attachReady,
                      "the device reports the person's configuration, not a handshake")
        checks.expect(agent.sharedInterrupt, "session/cancel from any client stops the turn")
        checks.expect(agent.sharedSettings, "and set_config_option retunes it for everyone")
        checks.expect(!agent.sharedAttachments, "while a Grok prompt carries no images")
        checks.expect(!agent.supports(.takeover), "there is nothing to take over from")
        checks.noThrow("the attachment fields survive a re-encode") {
            let encoded = try JSONValue.encode(agent)
            guard encoded["attach"]?.stringValue == "leader",
                  encoded["shared_interrupt"]?.boolValue == true,
                  encoded["shared_settings"]?.boolValue == true,
                  encoded["shared_attachments"]?.boolValue == false else {
                throw ProtocolFailure.malformed("the leader fields changed")
            }
        }
    }

    private static func events(checks: CheckRunner) {
        // Amendment A3: an inbound attachment carries a size, not bytes.
        if let json = FixtureSource.json("events/user_message.json"),
           let event = try? json.decode(SessionEvent.self), let message = event.userMessage {
            checks.equal(message.attachments.first?.size, 284_913, "an inbound attachment carries its size")
            checks.equal(message.attachments.first?.mime, "image/png", "an inbound attachment carries its type")
            checks.equal(message.source, .remote, "a remote message is attributed to the app")
        } else {
            checks.expect(false, "events/user_message.json decodes as a user message")
        }

        // Amendment A1: the tool category rides in `tool_kind`.
        for (file, kind) in [("events/tool_call.shell.json", ToolKind.shell),
                             ("events/tool_call.read.json", .read),
                             ("events/tool_call.edit.json", .edit),
                             ("events/tool_call.write.json", .write),
                             ("events/tool_call.search.json", .search),
                             ("events/tool_call.web.json", .web),
                             ("events/tool_call.mcp.json", .mcp),
                             ("events/tool_call.subagent.json", .subagent),
                             ("events/tool_call.todo.json", .todo),
                             ("events/tool_call.other.json", .other)] {
            guard let json = FixtureSource.json(file),
                  let event = try? json.decode(SessionEvent.self) else {
                checks.expect(false, "\(file) decodes")
                continue
            }
            checks.equal(event.kind, SessionEvent.toolCallKind, "\(file) is a tool_call event")
            checks.equal(event.toolCall?.kind, kind, "\(file) reports tool_kind \(kind)")
        }

        if let json = FixtureSource.json("events/tool_call.edit.json"),
           let event = try? json.decode(SessionEvent.self), let call = event.toolCall {
            checks.equal(call.diff?.additions, 4, "a diff carries its addition count")
            checks.equal(call.diff?.deletions, 2, "a diff carries its deletion count")
            checks.expect(call.diff?.patch?.contains("@@") == true, "a diff carries a unified patch")
            checks.expect(!call.isTruncated, "an untruncated tool call says so")
        } else {
            checks.expect(false, "events/tool_call.edit.json exposes a diff")
        }

        if let json = FixtureSource.json("events/approval.pending.json"),
           let event = try? json.decode(SessionEvent.self), let approval = event.approval {
            checks.equal(approval.kind, .edit, "an approval reports tool_kind")
            checks.equal(approval.primaryOption?.style, .primary, "an approval offers a primary option")
            checks.equal(approval.dangerOption?.style, .danger, "an approval offers a danger option")
            checks.expect(approval.status.isActionable, "a pending approval is actionable")
            checks.expect(approval.options.count >= 2, "every option the device sent is kept")
        } else {
            checks.expect(false, "events/approval.pending.json decodes as an approval")
        }

        if let json = FixtureSource.json("events/approval.resolved.json"),
           let event = try? json.decode(SessionEvent.self), let approval = event.approval {
            checks.expect(!approval.status.isActionable, "a resolved approval stops being actionable")
            checks.expect(approval.decision != nil, "a resolved approval records the decision")
        } else {
            checks.expect(false, "events/approval.resolved.json decodes")
        }

        if let json = FixtureSource.json("events/question.pending.json"),
           let event = try? json.decode(SessionEvent.self), let question = event.question {
            checks.expect(!question.questions.isEmpty, "a question carries at least one prompt")
            checks.expect(question.status.isActionable, "a pending question is actionable")
        } else {
            checks.expect(false, "events/question.pending.json decodes")
        }

        if let json = FixtureSource.json("events/question.resolved.json"),
           let event = try? json.decode(SessionEvent.self), let question = event.question {
            checks.expect(question.answers?.isEmpty == false, "a resolved question carries answers")
            checks.expect(question.by == nil, "a question answered here names no other source")
        } else {
            checks.expect(false, "events/question.resolved.json decodes")
        }

        // Amendment A20: the person at the terminal answered their own dialog
        // first, so the card here says where the answer came from.
        if let json = FixtureSource.json("events/question.resolved.terminal.json"),
           let event = try? json.decode(SessionEvent.self), let question = event.question {
            checks.equal(question.status, .resolved, "a question answered in the terminal resolves")
            checks.equal(question.by, .terminal, "and is attributed to the terminal")
            checks.expect(question.answers?.isEmpty == false, "with the answers it was given there")
            checks.noThrow("the attribution survives a re-encode") {
                guard try JSONValue.encode(event)["by"]?.stringValue == "terminal" else {
                    throw ProtocolFailure.malformed("question.by was lost")
                }
            }
        } else {
            checks.expect(false, "events/question.resolved.terminal.json decodes")
        }

        if let json = FixtureSource.json("events/assistant_text.delta.json"),
           let event = try? json.decode(SessionEvent.self) {
            checks.expect(event.streamText?.done == false, "a delta event is not done")
            checks.expect(event.streamText?.delta != nil, "a delta event carries a delta")
            checks.expect(event.isStreaming, "a delta event is still streaming")
        } else {
            checks.expect(false, "events/assistant_text.delta.json decodes")
        }

        if let json = FixtureSource.json("events/assistant_text.done.json"),
           let event = try? json.decode(SessionEvent.self) {
            checks.expect(event.streamText?.done == true, "a final event is done")
            checks.expect(event.streamText?.delta == nil, "a final event carries no delta")
        } else {
            checks.expect(false, "events/assistant_text.done.json decodes")
        }

        // An unknown kind is kept whole rather than dropped.
        let unknown = try? JSONDecoder().decode(SessionEvent.self, from: Data(#"""
        {"seq": 5, "ts": 1, "kind": "screenshot", "block_id": "s1", "url": "x"}
        """#.utf8))
        if case .unknown(let kind, let raw) = unknown?.body {
            checks.equal(kind, "screenshot", "an unknown kind is preserved")
            checks.equal(raw["url"]?.stringValue, "x", "an unknown payload is preserved")
        } else {
            checks.expect(false, "an unknown event kind decodes to .unknown")
        }
        checks.noThrow("an unknown field on a known object is ignored") {
            let json: JSONValue = ["session_id": "s", "device_id": "d", "future_field": 7]
            _ = try json.decode(Session.self)
        }
        let state = try? JSONDecoder().decode(SessionState.self, from: Data(#""hibernating""#.utf8))
        checks.equal(state?.rawValue, "hibernating", "an unknown session state decodes rather than throwing")
    }

    private static func frames(checks: CheckRunner) {
        if let json = FixtureSource.json("app/session.event.json"),
           case .sessionEvent(let sessionID, let deviceID, let event)? = try? AppFrame(json: json) {
            checks.expect(!sessionID.isEmpty, "a session.event frame names its session")
            checks.expect(event.seq > 0, "a session.event frame carries a seq")
            // Amendment A5: the device id may be present, and is never required.
            checks.expect(deviceID == nil || deviceID?.isEmpty == false,
                          "a session.event device id is either absent or a real id")
        } else {
            checks.expect(false, "app/session.event.json decodes as a session event")
        }
        checks.noThrow("session.event without a device id still decodes") {
            let json: JSONValue = ["type": "session.event", "session_id": "s",
                                   "event": ["seq": 1, "ts": 1, "kind": "notice",
                                             "level": "info", "text": "x"]]
            guard case .sessionEvent(_, let deviceID, _) = try AppFrame(json: json), deviceID == nil else {
                throw ProtocolFailure.malformed("session.event device id")
            }
        }
        checks.noThrow("session.removed without a device id still decodes") {
            let json: JSONValue = ["type": "session.removed", "session_id": "s"]
            guard case .sessionRemoved(let sessionID, let deviceID) = try AppFrame(json: json),
                  sessionID == "s", deviceID == nil else {
                throw ProtocolFailure.malformed("session.removed device id")
            }
        }
        if let json = FixtureSource.json("app/pairing.progress.json"),
           case .pairingProgress(let progress)? = try? AppFrame(json: json) {
            checks.expect(!progress.code.isEmpty, "pairing progress names the code")
        } else {
            checks.expect(false, "app/pairing.progress.json decodes")
        }
        if let json = FixtureSource.json("app/ping.json"), case .ping? = try? AppFrame(json: json) {
            checks.expect(true, "app/ping.json decodes as a ping")
        } else {
            checks.expect(false, "app/ping.json decodes as a ping")
        }
        if let json = FixtureSource.json("app/reply.error.json"),
           case .reply(_, .failure(let error))? = try? AppFrame(json: json) {
            checks.expect(!error.message.isEmpty, "an error reply carries a message")
            checks.expect(!error.code.rawValue.isEmpty, "an error reply carries a code")
        } else {
            checks.expect(false, "app/reply.error.json decodes as a failed reply")
        }
        if let json = FixtureSource.json("app/device.removed.json"),
           case .deviceRemoved(let deviceID)? = try? AppFrame(json: json) {
            checks.expect(!deviceID.isEmpty, "device.removed names the device")
        } else {
            checks.expect(false, "app/device.removed.json decodes")
        }

        // Typed reply results.
        if let json = FixtureSource.json("replay/subscribe.reply.json"), let result = json["result"] {
            checks.noThrow("a subscribe reply decodes") {
                let subscribe = try result.decode(SubscribeResult.self)
                guard subscribe.session.sessionID.isEmpty == false else {
                    throw ProtocolFailure.malformed("subscribe result")
                }
            }
        } else {
            checks.expect(false, "replay/subscribe.reply.json has a result")
        }
        // Amendment A6: the queue snapshot on a subscribe reply is optional.
        checks.noThrow("a subscribe reply carries an optional queue") {
            let withQueue: JSONValue = [
                "session": ["session_id": "s", "device_id": "d"],
                "events": [], "resync": false,
                "queue": ["pending": [["id": "q1", "text": "later", "ts": 1]]]
            ]
            guard try withQueue.decode(SubscribeResult.self).queue?.pending.count == 1 else {
                throw ProtocolFailure.malformed("subscribe queue")
            }
            let withoutQueue: JSONValue = ["session": ["session_id": "s", "device_id": "d"]]
            guard try withoutQueue.decode(SubscribeResult.self).queue == nil else {
                throw ProtocolFailure.malformed("subscribe queue absence")
            }
        }
        if let json = FixtureSource.json("history/page.json"), let result = json["result"] {
            checks.noThrow("a history reply decodes") {
                let page = try result.decode(HistoryResult.self)
                // Section 8: history never carries deltas, status, meta or queue.
                for event in page.events {
                    guard event.streamText?.delta == nil else {
                        throw ProtocolFailure.malformed("history contains a delta")
                    }
                    guard ![SessionEvent.statusKind, SessionEvent.metaKind, SessionEvent.queueKind]
                        .contains(event.kind) else {
                        throw ProtocolFailure.malformed("history contains \(event.kind)")
                    }
                }
                var previous = 0
                for event in page.events {
                    guard event.seq > previous else { throw ProtocolFailure.malformed("history is not ascending") }
                    previous = event.seq
                }
            }
        } else {
            checks.expect(false, "history/page.json has a result")
        }
        for (file, decode) in [
            ("app/reply.session.create.json", { (value: JSONValue) in try value.decode(SessionResult.self).session.sessionID }),
            ("app/reply.session.set.json", { try $0.decode(SessionResult.self).session.sessionID }),
            ("app/reply.session.takeover.json", { try $0.decode(SessionResult.self).session.sessionID }),
            ("app/reply.session.archive.json", { try $0.decode(SessionResult.self).session.sessionID })
        ] {
            if let json = FixtureSource.json(file), let result = json["result"] {
                checks.noThrow("\(file) decodes as a session result") {
                    guard try decode(result).isEmpty == false else {
                        throw ProtocolFailure.malformed(file)
                    }
                }
            } else {
                checks.expect(false, "\(file) has a result")
            }
        }
        if let json = FixtureSource.json("app/reply.session.send.json"), let result = json["result"] {
            checks.noThrow("a send reply decodes") { _ = try result.decode(SendResult.self) }
        }
        if let json = FixtureSource.json("app/reply.session.block.json"), let result = json["result"] {
            checks.noThrow("a block reply decodes") { _ = try result.decode(BlockResult.self) }
        }
        if let json = FixtureSource.json("app/reply.device.dirs.json"), let result = json["result"] {
            checks.noThrow("a directory listing decodes") {
                let listing = try result.decode(DirectoryListing.self)
                guard !listing.path.isEmpty, !listing.entries.isEmpty else {
                    throw ProtocolFailure.malformed("directory listing")
                }
            }
        }
        if let json = FixtureSource.json("app/reply.device.git.json"), let result = json["result"] {
            checks.noThrow("a git status decodes") { _ = try result.decode(GitStatus.self) }
        }
        if let json = FixtureSource.json("app/reply.device.agents.json"), let result = json["result"] {
            checks.noThrow("an agents listing decodes") {
                guard try result.decode(AgentsResult.self).agents.isEmpty == false else {
                    throw ProtocolFailure.malformed("agents")
                }
            }
            // Amendment A21: only the agent that offers a tier carries one, and
            // the other reads as an empty list rather than as a missing field.
            let agents = (try? result.decode(AgentsResult.self).agents) ?? []
            checks.equal(agents.first { $0.agent == "codex" }?.speeds.map(\.id), ["priority"],
                         "Codex advertises its faster tier")
            checks.equal(agents.first { $0.agent == "codex" }?.speedLabel("priority"), "Fast",
                         "with the name the agent gave it")
            checks.equal(agents.first { $0.agent == "claude" }?.speeds.isEmpty, true,
                         "and an agent that has none lists none")
        }
    }

    /// Amendment A22: the build a gateway serves, the build a device runs, and
    /// the rule the row draws from the two of them.
    private static func deviceUpdates(checks: CheckRunner) {
        guard let config = FixtureSource.json("http/config.response.json"),
              let served = try? config.decode(GatewayConfig.self).servedBuild else {
            checks.expect(false, "http/config.response.json names the served client build")
            return
        }
        checks.equal(served.count, 64, "the served build is a SHA-256")
        guard let list = FixtureSource.json("http/devices.list.response.json"),
              let devices = try? list.decode(DeviceListResponse.self).devices, devices.count == 2 else {
            checks.expect(false, "the device list decodes both devices")
            return
        }
        checks.equal(devices[0].clientBuild, served, "the first device runs the served build")
        checks.equal(devices[0].updateState, .idle, "and reports no update in flight")
        checks.expect(devices[0].updateMessage == nil, "and carries no update message")
        checks.expect(!DeviceUpdate.isBehind(devices[0], servedBuild: served),
                      "so it is not offered an update")
        checks.equal(DeviceUpdate.block(for: devices[0], servedBuild: served), .current,
                     "and Update says why it cannot act")
        checks.equal(DeviceUpdate.block(for: devices[1], servedBuild: served), .offline,
                     "an offline device is not asked to update")

        // A device the gateway has not heard a build from is behind whatever
        // the gateway serves: an unknown build is not the served one.
        var unknown = devices[0]
        unknown.clientBuild = nil
        checks.expect(DeviceUpdate.isBehind(unknown, servedBuild: served), "an unknown build is behind")
        checks.equal(DeviceUpdate.notice(for: unknown, servedBuild: served), .available,
                     "and the row says an update is available")
        checks.expect(DeviceUpdate.notice(for: devices[0], servedBuild: nil) == nil,
                      "a gateway with no wheel puts nothing on the row")

        guard let updated = FixtureSource.json("app/device.updated.json"),
              let frame = try? AppFrame(json: updated), case .deviceUpdated(let device) = frame else {
            checks.expect(false, "app/device.updated.json decodes as a device frame")
            return
        }
        checks.equal(device.clientBuild, served, "device.updated carries the build the device runs")

        if let hello = FixtureSource.json("device/hello.json") {
            checks.equal(hello["client_build"]?.stringValue, served,
                         "the device's own hello reports the same build")
        }
        if let request = FixtureSource.json("app/device.update.json") {
            let built = GatewayRequest.updateDevice(
                deviceID: request["device_id"]?.stringValue ?? "",
                build: request["build"]?.stringValue ?? "")
            checks.equal(built.type, request["type"]?.stringValue, "device.update is built as the fixture")
            checks.equal(built.body["build"], request["build"], "with the build the gateway serves")
        }
        if let reply = FixtureSource.json("app/reply.device.update.json") {
            checks.noThrow("a device's acceptance decodes") {
                let result = try (reply["result"] ?? .object([:])).decode(DeviceUpdateResult.self)
                guard result.accepted, result.from?.count == 64 else {
                    throw ProtocolFailure.malformed("device.update reply")
                }
            }
        }
        if let failure = FixtureSource.json("device/update.failed.json") {
            checks.expect(failure["message"]?.stringValue?.isEmpty == false,
                          "update.failed says why the update did not complete")
        }
    }

    /// Amendment A23: the link a host prints, and the code claiming it returns.
    private static func claimTokens(checks: CheckRunner) {
        guard let request = FixtureSource.json("http/devices.pairing.request.response.json"),
              let claimURL = request["claim_url"]?.stringValue,
              let token = request["token"]?.stringValue else {
            checks.expect(false, "http/devices.pairing.request.response.json exists")
            return
        }
        guard let gateway = try? GatewayEndpoint("https://rc.example.com") else {
            checks.expect(false, "the fixture's origin parses")
            return
        }
        checks.equal(PairingClaimLink(payload: claimURL, gateway: gateway)?.token, token,
                     "the printed link carries the claim token")
        checks.expect(PairingClaimLink(payload: claimURL,
                                       gateway: (try? GatewayEndpoint("https://other.example.com"))
                                        ?? .placeholder) == nil,
                      "and a link for another gateway is not ours to claim")
        checks.expect(PairingClaimLink(payload: "https://rc.example.com/pair", gateway: gateway) == nil,
                      "a link with no token is refused")

        if let status = FixtureSource.json("http/devices.pairing.request.status.response.json") {
            checks.equal(status["status"]?.stringValue, "claimed", "a claimed poll carries the code")
        }
        if let claim = FixtureSource.json("http/devices.pairing.claim.response.json") {
            checks.noThrow("a claim response decodes") {
                let decoded = try claim.decode(PairingClaim.self)
                guard decoded.code.hasPrefix("RC-"), decoded.expiresAt > 0 else {
                    throw ProtocolFailure.malformed("pairing claim")
                }
            }
        } else {
            checks.expect(false, "http/devices.pairing.claim.response.json exists")
        }
    }

    private static func http(checks: CheckRunner) {
        if let json = FixtureSource.json("http/login.response.json") {
            checks.noThrow("a login response decodes") {
                guard try json.decode(LoginResponse.self).token.isEmpty == false else {
                    throw ProtocolFailure.malformed("login")
                }
            }
        } else {
            checks.expect(false, "http/login.response.json exists")
        }
        if let json = FixtureSource.json("http/config.response.json") {
            checks.noThrow("a config response decodes") {
                let config = try json.decode(GatewayConfig.self)
                guard !config.publicOrigin.isEmpty else { throw ProtocolFailure.malformed("config") }
            }
        }
        if let json = FixtureSource.json("http/auth.session.response.json") {
            checks.noThrow("a session response decodes") { _ = try json.decode(SessionInfoResponse.self) }
        }
        if let json = FixtureSource.json("http/devices.list.response.json") {
            checks.noThrow("a device list decodes") { _ = try json.decode(DeviceListResponse.self) }
        }
        deviceUpdates(checks: checks)
        claimTokens(checks: checks)
        if let json = FixtureSource.json("http/devices.patch.response.json") {
            checks.noThrow("a renamed device decodes") { _ = try json.decode(DeviceResponse.self) }
        }
        if let json = FixtureSource.json("http/sessions.list.response.json") {
            checks.noThrow("a session list decodes") { _ = try json.decode(SessionListResponse.self) }
        }
        if let json = FixtureSource.json("http/devices.pairing.response.json") {
            checks.noThrow("a pairing grant decodes") {
                let grant = try json.decode(PairingGrant.self)
                guard grant.install.macos.contains(grant.code) else {
                    throw ProtocolFailure.malformed("the install command must carry the code")
                }
            }
        } else {
            checks.expect(false, "http/devices.pairing.response.json exists")
        }
        if let json = FixtureSource.json("http/push.payload.json") {
            checks.noThrow("a push payload decodes into a route") {
                let route = try PushRoute(userInfo: JSONEncoder().encode(json))
                guard route.deepLink != nil, !route.deviceName.isEmpty else {
                    throw ProtocolFailure.malformed("push route")
                }
            }
        } else {
            checks.expect(false, "http/push.payload.json exists")
        }
        if let json = FixtureSource.json("http/push.apns.register.request.json") {
            checks.noThrow("an APNs registration decodes") {
                let registration = try json.decode(APNSRegistration.self)
                guard !registration.token.isEmpty, !registration.bundleID.isEmpty else {
                    throw ProtocolFailure.malformed("apns registration")
                }
            }
        }
        checks.throwsError("a push payload from a newer protocol is refused") {
            _ = try PushRoute(userInfo: Data(#"{"rc": {"v": 2, "device_id": "d", "session_id": "s"}}"#.utf8))
        }
        checks.expect(SessionLink(url: URL(string: "remotecontrol://session?device=d&id=s")!)?.sessionID == "s",
                      "a deep link parses back")
        checks.expect(SessionLink(url: URL(string: "https://example.com/session?device=d&id=s")!) == nil,
                      "a web URL is not a session link")
    }

    /// The request builders must produce the exact frames in `fixtures/app`.
    /// Amendment A27: the list a session offers, what running one looks like in
    /// the transcript, and which agents take them at all.
    private static func commands(checks: CheckRunner) {
        guard let reply = FixtureSource.json("app/reply.session.commands.json"),
              let result = try? (reply["result"] ?? .object([:])).decode(CommandsResult.self) else {
            checks.expect(false, "app/reply.session.commands.json decodes as a command list")
            return
        }
        checks.equal(result.commands.map(\.name),
                     ["compact", "review", "init", "status", "release-notes", "skill:pdf-tables"],
                     "the worked list decodes in the order the device sent it")
        checks.equal(result.commands.first { $0.name == "review" }?.argument, "instructions",
                     "a command that takes an argument names the placeholder")
        checks.expect(result.commands.first { $0.name == "compact" }?.takesArgument == false,
                      "and one that takes none has no placeholder to show")
        checks.equal(CommandSection.build(result.commands).map(\.title),
                     ["Built-in", "Prompts", "Skills"],
                     "groups section the list in the order they first appear")
        checks.equal(result.commands.first?.slash, "/compact", "a row is drawn with its slash")
        checks.equal(result.commands.first { $0.name == "review" }?.line(argument: "the retry logic"),
                     "/review the retry logic", "and echoed as the line the user typed")
        checks.equal(try? JSONValue.object([:]).decode(CommandsResult.self).commands.count, 0,
                     "a device with nothing to offer answers an empty list rather than a failure")

        // A command a terminal would have printed the answer to reads as a tool
        // call titled with the command itself.
        if let event = FixtureSource.json("events/tool_call.command.json"),
           let decoded = try? event.decode(SessionEvent.self), let call = decoded.toolCall {
            checks.equal(call.tool, "/usage", "the block is named after the command")
            checks.equal(call.title, "/usage", "and titled with it")
            checks.equal(call.kind, ToolKind.other, "information is not a shell call or an edit")
            checks.equal(call.status, ToolStatus.succeeded, "and arrives finished")
        } else {
            checks.expect(false, "events/tool_call.command.json decodes as a tool call")
        }

        // The capability, from the protocol's own worked examples.
        for (file, offers) in [("agent.codex-daemon.json", true), ("agent.grok.json", true),
                               ("agent.pi.json", true), ("agent.claude-attach.json", false)] {
            guard let agent = try? (FixtureSource.json("objects/\(file)") ?? .null).decode(AgentInfo.self)
            else {
                checks.expect(false, "objects/\(file) decodes")
                continue
            }
            checks.equal(agent.supports(.commands), offers,
                         "\(agent.agent) \(offers ? "takes" : "takes no") commands from an app")
        }
    }

    private static func requests(checks: CheckRunner) {
        func compare(_ built: GatewayRequest, with file: String, ignoring: Set<String> = []) {
            guard let expected = FixtureSource.json(file)?.objectValue else {
                checks.expect(false, "\(file) exists")
                return
            }
            let actual = built.json.objectValue ?? [:]
            let skip = ignoring.union(["id"])
            for (key, value) in expected where !skip.contains(key) {
                checks.equal(actual[key], value, "\(file) field \(key)")
            }
            for key in actual.keys where !skip.contains(key) && expected[key] == nil {
                checks.expect(false, "\(file) has no field \(key), but the app sends one")
            }
            if expected["id"] != nil {
                checks.expect(actual["id"]?.stringValue?.isEmpty == false, "\(file) carries a request id")
            }
        }

        guard let send = FixtureSource.json("app/session.send.json")?.objectValue else {
            checks.expect(false, "app/session.send.json exists")
            return
        }
        let attachment = send.array("attachments").first?.objectValue
        let bytes = Data(base64Encoded: attachment?.string("data_base64") ?? "") ?? Data()
        checks.noThrow("session.send matches the fixture") {
            let request = try GatewayRequest.send(
                sessionID: send.string("session_id") ?? "", text: send.string("text") ?? "",
                attachments: [OutboundAttachment(name: attachment?.string("name") ?? "",
                                                 mime: attachment?.string("mime") ?? "", data: bytes)],
                mode: SendMode(rawValue: send.string("mode") ?? "auto"))
            compare(request, with: "app/session.send.json")
        }
        checks.equal(bytes.isEmpty, false, "the fixture attachment decodes from base64")

        if let subscribe = FixtureSource.json("app/session.subscribe.json")?.objectValue {
            compare(GatewayRequest.subscribe(sessionID: subscribe.string("session_id") ?? "",
                                             sinceSeq: subscribe.int("since_seq")),
                    with: "app/session.subscribe.json")
        }
        if let create = FixtureSource.json("app/session.create.json")?.objectValue {
            compare(GatewayRequest.createSession(
                deviceID: create.string("device_id") ?? "", agent: create.string("agent") ?? "",
                cwd: create.string("cwd") ?? "", model: create.string("model"),
                permissionMode: create.string("permission_mode"), effort: create.string("effort"),
                speed: create["speed"].map { SpeedChange(id: $0.stringValue) },
                worktree: create.bool("worktree"), firstMessage: create.string("first_message"),
                title: create.string("title")), with: "app/session.create.json")
        }
        if let approve = FixtureSource.json("app/session.approve.json")?.objectValue {
            compare(GatewayRequest.approve(sessionID: approve.string("session_id") ?? "",
                                           requestID: approve.string("request_id") ?? "",
                                           optionID: approve.string("option_id") ?? "",
                                           message: approve.string("message")),
                    with: "app/session.approve.json")
        }
        if let answer = FixtureSource.json("app/session.answer.json")?.objectValue {
            checks.noThrow("session.answer matches the fixture") {
                let answers = try (answer["answers"] ?? .object([:])).decode([String: QuestionAnswer].self)
                compare(try GatewayRequest.answer(sessionID: answer.string("session_id") ?? "",
                                                  requestID: answer.string("request_id") ?? "",
                                                  answers: answers),
                        with: "app/session.answer.json")
            }
        }
        if let set = FixtureSource.json("app/session.set.json")?.objectValue {
            compare(GatewayRequest.set(sessionID: set.string("session_id") ?? "", model: set.string("model"),
                                       permissionMode: set.string("permission_mode"),
                                       effort: set.string("effort"),
                                       speed: set["speed"].map { SpeedChange(id: $0.stringValue) },
                                       title: set.string("title")),
                    with: "app/session.set.json")
        }
        // Amendment A21: going back to the standard speed is `speed: null`, and
        // saying nothing about the tier is the key not being there at all.
        checks.equal(GatewayRequest.set(sessionID: "s", speed: .standard).json["speed"], JSONValue.null,
                     "session.set puts a session back to the standard speed with a null")
        checks.equal(GatewayRequest.set(sessionID: "s").json["speed"], nil,
                     "and leaves the tier alone by not naming it")
        // 5.11 the same way round: a `meta` that names the key with a null put
        // the session back to the standard speed, and one that omits it said
        // nothing about the speed at all.
        checks.noThrow("a meta tells a cleared tier from an unmentioned one") {
            let cleared: JSONValue = ["seq": 1, "ts": 1, "kind": "meta", "speed": .null]
            guard try cleared.decode(SessionEvent.self).meta?.speed == SpeedChange.standard else {
                throw ProtocolFailure.malformed("meta speed null")
            }
            let raised: JSONValue = ["seq": 2, "ts": 2, "kind": "meta", "speed": "priority"]
            guard try raised.decode(SessionEvent.self).meta?.speed == SpeedChange.tier("priority") else {
                throw ProtocolFailure.malformed("meta speed tier")
            }
            let silent: JSONValue = ["seq": 3, "ts": 3, "kind": "meta", "model": "gpt-5.4-codex"]
            guard try silent.decode(SessionEvent.self).meta?.speed == nil else {
                throw ProtocolFailure.malformed("meta speed absent")
            }
        }
        if let history = FixtureSource.json("app/session.history.json")?.objectValue {
            compare(GatewayRequest.history(sessionID: history.string("session_id") ?? "",
                                           beforeSeq: history.int("before_seq"),
                                           limit: history.int("limit") ?? RequestLimits.historyPageSize),
                    with: "app/session.history.json")
        }
        if let block = FixtureSource.json("app/session.block.json")?.objectValue {
            compare(GatewayRequest.block(sessionID: block.string("session_id") ?? "",
                                         blockID: block.string("block_id") ?? ""),
                    with: "app/session.block.json")
        }
        if let list = FixtureSource.json("app/session.commands.json")?.objectValue {
            compare(GatewayRequest.commands(sessionID: list.string("session_id") ?? ""),
                    with: "app/session.commands.json")
        }
        if let run = FixtureSource.json("app/session.command.json")?.objectValue {
            compare(GatewayRequest.command(sessionID: run.string("session_id") ?? "",
                                           name: run.string("name") ?? "",
                                           argument: run.string("argument")),
                    with: "app/session.command.json")
        }
        // Amendment A27: a command that takes nothing sends no argument at all,
        // rather than an empty one the device would have to read as a value.
        checks.equal(GatewayRequest.command(sessionID: "s", name: "compact").json["argument"], nil,
                     "session.command omits an argument it was not given")
        if let remove = FixtureSource.json("app/session.queue_remove.json")?.objectValue {
            compare(GatewayRequest.queueRemove(sessionID: remove.string("session_id") ?? "",
                                               queuedID: remove.string("queued_id") ?? ""),
                    with: "app/session.queue_remove.json")
        }
        if let archive = FixtureSource.json("app/session.archive.json")?.objectValue {
            compare(GatewayRequest.archive(sessionID: archive.string("session_id") ?? "",
                                           archived: archive.bool("archived") ?? true),
                    with: "app/session.archive.json")
        }
        for (file, build) in [
            ("app/session.stop.json", GatewayRequest.stop(sessionID:)),
            ("app/session.takeover.json", GatewayRequest.takeover(sessionID:)),
            ("app/session.delete.json", GatewayRequest.delete(sessionID:))
        ] {
            if let json = FixtureSource.json(file)?.objectValue {
                compare(build(json.string("session_id") ?? ""), with: file)
            }
        }
        if let dirs = FixtureSource.json("app/device.dirs.json")?.objectValue {
            compare(GatewayRequest.dirs(deviceID: dirs.string("device_id") ?? "", path: dirs.string("path")),
                    with: "app/device.dirs.json")
        }
        if let git = FixtureSource.json("app/device.git.json")?.objectValue {
            compare(GatewayRequest.git(deviceID: git.string("device_id") ?? "", path: git.string("path") ?? ""),
                    with: "app/device.git.json")
        }
        if let agents = FixtureSource.json("app/device.agents.json")?.objectValue {
            compare(GatewayRequest.agents(deviceID: agents.string("device_id") ?? ""),
                    with: "app/device.agents.json")
        }
        if let unsubscribe = FixtureSource.json("app/session.unsubscribe.json")?.objectValue {
            compare(GatewayRequest.unsubscribe(sessionID: unsubscribe.string("session_id") ?? ""),
                    with: "app/session.unsubscribe.json")
        }
        compare(GatewayRequest.pong(), with: "app/pong.json")

        // Bounds are checked before a message can cost the user their draft.
        checks.throwsError("more than eight attachments is refused before sending") {
            let attachment = OutboundAttachment(name: "a", mime: "image/png", data: Data([0]))
            _ = try GatewayRequest.send(sessionID: "s", text: "x",
                                        attachments: Array(repeating: attachment, count: 9))
        }
        checks.throwsError("an oversized attachment is refused before sending") {
            _ = try GatewayRequest.send(sessionID: "s", text: "x", attachments: [
                OutboundAttachment(name: "big", mime: "image/png",
                                   data: Data(count: RequestLimits.maxAttachmentBytes + 1))
            ])
        }
        checks.equal(GatewayRequest.history(sessionID: "s", limit: 5_000).json["limit"]?.intValue,
                     RequestLimits.maxHistoryPageSize, "the history limit is clamped")
        checks.noThrow("a retry keeps the original request id") {
            let first = try GatewayRequest.send(id: "fixed", sessionID: "s", text: "x")
            let retry = try GatewayRequest.send(id: "fixed", sessionID: "s", text: "x")
            guard first.id == retry.id, first.json["id"]?.stringValue == "fixed" else {
                throw ProtocolFailure.malformed("retry id")
            }
        }
        checks.expect(GatewayRequest.pong().json["id"] == nil, "pong carries no request id")
        checks.expect(GatewayRequest.unsubscribe(sessionID: "s").json["id"] == nil,
                      "unsubscribe carries no request id")
    }

    /// Amendment A8: `first_seq` is optional, decoded, and survives a re-encode.
    private static func firstSeq(checks: CheckRunner) {
        var seen = 0
        for file in FixtureSource.files(in: FixtureSource.fixtures.appending(path: "events")) {
            guard let data = try? Data(contentsOf: file),
                  let json = try? JSONDecoder().decode(JSONValue.self, from: data),
                  let declared = json["first_seq"]?.intValue,
                  let event = try? json.decode(SessionEvent.self) else { continue }
            seen += 1
            checks.equal(event.firstSeq, declared, "\(FixtureSource.label(file)) decodes first_seq")
            checks.equal(event.orderSeq, declared, "\(FixtureSource.label(file)) orders by first_seq")
            checks.equal((try? JSONValue.encode(event))?["first_seq"]?.intValue, declared,
                         "\(FixtureSource.label(file)) keeps first_seq on re-encoding")
        }
        checks.expect(seen > 0, "at least one event fixture carries first_seq")

        checks.noThrow("an event without first_seq orders by its own seq") {
            let event = try JSONValue.object([
                "seq": 7, "ts": 1, "kind": "assistant_text", "block_id": "a", "text": "x", "done": true
            ]).decode(SessionEvent.self)
            guard event.firstSeq == nil, event.orderSeq == 7 else {
                throw ProtocolFailure.malformed("first_seq default")
            }
        }
    }

    private static func toolKinds(checks: CheckRunner) {
        checks.equal(ToolKind.derived(fromTool: "Bash"), .shell, "Bash falls back to shell")
        checks.equal(ToolKind.derived(fromTool: "Edit"), .edit, "Edit falls back to edit")
        checks.equal(ToolKind.derived(fromTool: "Task"), .subagent, "Task falls back to sub-agent")
        checks.equal(ToolKind.derived(fromTool: "mcp__linear__issue"), .mcp, "an MCP tool falls back to mcp")
        checks.equal(ToolKind.derived(fromTool: "Whatever"), .other, "an unknown tool falls back to other")
    }
}
