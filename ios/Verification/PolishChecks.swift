import Foundation
import RCCore

/// Amendments A29, A30 and A31 — dictation polish, words another agent put in
/// the conversation, and the oldest app build a gateway will talk to.
///
/// All three are rules rather than screens, so all three are checked here: the
/// span arithmetic and the context the model is given, the caption a message
/// nobody typed carries, and the comparison that decides whether the app may
/// go on at all.
enum PolishChecks {
    @MainActor
    static func run() async -> CheckResult {
        let checks = CheckRunner(group: "polish")
        span(checks)
        context(checks)
        contract(checks)
        await flow(checks)
        settings(checks)
        agentMessages(checks)
        versions(checks)
        await minimumVersion(checks)
        return checks.result()
    }

    // MARK: - A29, the span and its two drafts

    private static func span(_ checks: CheckRunner) {
        let empty = DictationSpan(base: "", dictated: "um the the green blinking thing")
        checks.equal(empty.dictatedDraft, "um the the green blinking thing",
                     "a dictation into an empty field is the whole draft")
        checks.equal(empty.polishedDraft("The pulsing status dot."), "The pulsing status dot.",
                     "and the answer replaces the whole of it")

        let typed = DictationSpan(base: "Two things:", dictated: "um fix the dot")
        checks.equal(typed.dictatedDraft, "Two things:\num fix the dot",
                     "a dictation after typed words goes on its own line")
        checks.equal(typed.polishedDraft("fix the dot"), "Two things:\nfix the dot",
                     "and only the dictated half is replaced")

        let spaced = DictationSpan(base: "Two things: ", dictated: "fix the dot")
        checks.equal(spaced.dictatedDraft, "Two things: fix the dot",
                     "a draft that already ends in whitespace takes no newline")

        // The join is the composer's own, so a span rebuilds exactly the draft
        // dictation produced rather than something close to it.
        let target = VoiceDraftTarget(account: "a", deviceID: "d", sessionID: "s")
        checks.equal(target.inserting("um fix the dot", into: "Two things:", currentTarget: target),
                     typed.dictatedDraft,
                     "the span's join is the one dictation itself uses")

        checks.equal(DictationPolish.applyPolished(current: typed.dictatedDraft, span: typed,
                                                   polished: "  fix the dot.  "),
                     "Two things:\nfix the dot.",
                     "the answer is trimmed and lands in the dictated span")
        checks.equal(DictationPolish.applyPolished(current: "something else", span: typed,
                                                   polished: "fix the dot."), nil,
                     "a field that has moved on keeps what the person put in it")
        checks.equal(DictationPolish.applyPolished(current: typed.dictatedDraft, span: typed,
                                                   polished: "   "), nil,
                     "an empty answer is no answer")
        checks.equal(DictationPolish.undoPolished(current: "Two things:\nfix the dot.", span: typed,
                                                  polished: "fix the dot."),
                     typed.dictatedDraft,
                     "Undo puts the dictated words back")
        checks.equal(DictationPolish.undoPolished(current: "edited by hand", span: typed,
                                                  polished: "fix the dot."), nil,
                     "and puts nothing back once the field holds something else")

        checks.expect(DictationPolish.canPolish("um so"), "words can be polished")
        checks.expect(!DictationPolish.canPolish("   "), "whitespace cannot")
        checks.expect(!DictationPolish.canPolish(String(repeating: "a", count: 8193)),
                      "and neither can a dictation past the contract's limit")
    }

    // MARK: - A29, what the model is told

    @MainActor
    private static func context(_ checks: CheckRunner) {
        var timeline = Timeline()
        var seq = 0
        func next() -> Int { seq += 1; return seq }
        for index in 1...25 {
            timeline.apply(SessionEvent(
                seq: next(), ts: 0, kind: SessionEvent.userMessageKind, blockID: "u\(index)",
                body: .userMessage(UserMessagePayload(text: "question \(index)"))))
            timeline.apply(SessionEvent(
                seq: next(), ts: 0, kind: SessionEvent.assistantTextKind, blockID: "a\(index)",
                body: .assistantText(StreamTextPayload(text: "answer \(index)", done: true))))
        }
        // A tool call is not conversation, and neither is a turn marker.
        timeline.apply(SessionEvent(seq: next(), ts: 0, kind: SessionEvent.toolCallKind, blockID: "t1",
                                    body: .toolCall(ToolCallPayload(tool: "Bash", kind: .shell,
                                                                    title: "make test",
                                                                    status: .succeeded))))
        timeline.addOptimistic(OptimisticMessage(id: "pending", text: "and one just sent"))

        let items = DictationPolish.context(timeline)
        checks.equal(items.count, 20, "at most twenty messages go with a dictation")
        checks.equal(items.last?.text, "and one just sent",
                     "the newest of them is the send the device has not echoed yet")
        checks.equal(items.last?.role, .user, "which is the person's own")
        checks.equal(items.first?.text, "answer 16", "oldest first, counting back from the newest")
        checks.expect(items.allSatisfy { !$0.text.isEmpty }, "and nothing empty is sent")

        var long = Timeline()
        long.apply(SessionEvent(seq: 1, ts: 0, kind: SessionEvent.userMessageKind, blockID: "u",
                                body: .userMessage(UserMessagePayload(
                                    text: String(repeating: "x", count: 5000)))))
        checks.equal(DictationPolish.context(long).first?.text.count, 4000,
                     "each message is trimmed to what the contract takes")
    }

    // MARK: - A29, the request the contract fixes

    private static func contract(_ checks: CheckRunner) {
        let span = DictationSpan(base: "", dictated: "um the green blinking thing")
        let request = DictationPolish.request(
            span: span, model: "gpt-4.1-mini", strength: .strong, language: "en",
            context: [PolishContextItem(role: .user, text: "Make the dot pulse.")])
        checks.equal(request.text, span.dictated, "the dictated words are what is sent")
        checks.equal(request.strength, .strong, "with the strength the user chose")
        checks.equal(request.language, "en", "and the dictation language as a hint")
        checks.equal(DictationPolish.request(span: span, model: "m", strength: .moderate,
                                             language: "auto", context: []).language, nil,
                     "an automatic dictation language is no hint at all")

        // The frozen fixtures decode into the models the app sends and reads.
        checks.noThrow("http/polish.request.json decodes as a polish request") {
            guard let json = FixtureSource.json("http/polish.request.json") else {
                throw ProtocolFailure.malformed("http/polish.request.json")
            }
            let decoded = try json.decode(PolishRequest.self)
            guard decoded.strength == .strong, decoded.context.count == 2,
                  decoded.context.first?.role == .user, decoded.context.last?.role == .assistant else {
                throw ProtocolFailure.malformed("polish request fields")
            }
        }
        checks.noThrow("http/polish.response.json decodes as a polish response") {
            guard let json = FixtureSource.json("http/polish.response.json") else {
                throw ProtocolFailure.malformed("http/polish.response.json")
            }
            guard !(try json.decode(PolishResponse.self)).text.isEmpty else {
                throw ProtocolFailure.malformed("polish response text")
            }
        }
        checks.noThrow("http/polish.models.response.json decodes as the provider's models") {
            guard let json = FixtureSource.json("http/polish.models.response.json") else {
                throw ProtocolFailure.malformed("http/polish.models.response.json")
            }
            let models = try json.decode(PolishModelsResponse.self).models
            guard models.count == 2, models.first?.id == "gpt-4.1-mini" else {
                throw ProtocolFailure.malformed("polish models")
            }
        }

        // A gateway older than A29 says nothing about polishing, which is no.
        checks.noThrow("a hello without polish reports it disabled") {
            let hello = try JSONValue.object([
                "type": .string("hello"), "protocol": .number(1),
                "gateway_version": .string("0.0.9"),
                "user": .object(["username": .string("me")]),
                "devices": .array([]), "sessions": .array([]),
                "stt": .object(["enabled": .bool(false)]),
                "server_time": .number(0)
            ]).decode(HelloFrame.self)
            guard !hello.polish.enabled, hello.apps == nil else {
                throw ProtocolFailure.malformed("an older gateway asks for nothing")
            }
        }
        checks.expect(FixtureSource.json("app/hello.json")
            .flatMap { try? $0.decode(HelloFrame.self) }?.polish.enabled == true,
                      "the frozen hello reports the gateway can polish")
        checks.expect(FixtureSource.json("http/config.response.json")
            .flatMap { try? $0.decode(GatewayConfig.self) }?.polish.enabled == true,
                      "and so does the frozen config response")
    }

    // MARK: - A29, the flow the composer drives

    @MainActor
    private static func flow(_ checks: CheckRunner) async {
        func store() -> ChatStore {
            let session = DemoFixtures.sessions[0]
            let chat = ChatStore(session: session, channel: AcceptingChannel())
            chat.deviceOnline = true
            return chat
        }
        let span = DictationSpan(base: "Two things:", dictated: "um fix the the dot")

        // Success: the dictated span alone is replaced, and the note offers Undo.
        let success = store()
        success.draft = span.dictatedDraft
        success.polishService = { request in
            "Fix the dot. (\(request.strength.rawValue), \(request.context.count) messages)"
        }
        success.polish(span: span, model: "gpt-4.1-mini", strength: .moderate, language: "en")
        checks.equal(success.polishPhase, .polishing, "the request is out")
        checks.equal(success.statusLine, "Polishing…", "and the status line says so")
        await settle { success.polishPhase != .polishing }
        checks.equal(success.draft, "Two things:\nFix the dot. (moderate, 0 messages)",
                     "the answer lands in the dictated span and nowhere else")
        if case .polished = success.polishPhase { checks.expect(true, "the note offers Undo") }
        else { checks.expect(false, "the note offers Undo") }
        success.undoPolish()
        checks.equal(success.draft, span.dictatedDraft, "Undo puts the dictated words back")
        checks.equal(success.polishPhase, .idle, "and takes the note with them")

        // An edit ends the note; nothing else does.
        let edited = store()
        edited.draft = span.dictatedDraft
        edited.polishService = { _ in "Fix the dot." }
        edited.polish(span: span, model: "m", strength: .moderate, language: "en")
        await settle { edited.polishPhase != .polishing }
        edited.draft += " and the header"
        checks.equal(edited.polishPhase, .idle, "typing after a polish ends the note")

        // Failure: the words are left exactly as dictated.
        let failed = store()
        failed.draft = span.dictatedDraft
        failed.polishService = { _ in throw TransportError.notConnected }
        failed.polish(span: span, model: "m", strength: .strong, language: "auto")
        await settle { failed.polishPhase != .polishing }
        checks.equal(failed.polishPhase, .failed, "a failure says so")
        checks.equal(failed.draft, span.dictatedDraft, "and changes not one word")
        failed.clearPolishNote()
        checks.equal(failed.polishPhase, .idle, "the line goes once it has been read")

        // A send while polishing sends the words as dictated and drops the answer.
        let sending = store()
        sending.draft = span.dictatedDraft
        sending.polishService = { _ in
            try? await Task.sleep(for: .milliseconds(200))
            return "Fix the dot."
        }
        sending.polish(span: span, model: "m", strength: .moderate, language: "en")
        await sending.send()
        checks.equal(sending.polishPhase, .idle, "sending drops the request")
        checks.equal(sending.draft, "", "and the field is empty behind it")
        try? await Task.sleep(for: .milliseconds(350))
        checks.equal(sending.draft, "", "a late answer is not pasted into the next message")

        // Nothing runs without a model, and nothing runs without a service.
        let unconfigured = store()
        unconfigured.draft = span.dictatedDraft
        unconfigured.polishService = { _ in "Fix the dot." }
        unconfigured.polish(span: span, model: "", strength: .moderate, language: "en")
        checks.equal(unconfigured.polishPhase, .idle, "no model chosen, no request")

        let unserviced = store()
        unserviced.draft = span.dictatedDraft
        unserviced.polish(span: span, model: "m", strength: .moderate, language: "en")
        checks.equal(unserviced.polishPhase, .idle, "no polish service, no request")
    }

    // MARK: - A29, the settings that drive it

    @MainActor
    private static func settings(_ checks: CheckRunner) {
        let defaults = UserDefaults(suiteName: "polish.checks")!
        defaults.removePersistentDomain(forName: "polish.checks")
        let store = SettingsStore(defaults: defaults)
        checks.expect(!store.polishEnabled, "polishing is off on a fresh install")
        checks.equal(store.polishModel, "", "with no model chosen")
        checks.equal(store.polishStrength, .moderate, "and the gentler of the two strengths")

        store.remember(origin: "https://rc.example.com", username: "ada")
        store.polishEnabled = true
        store.polishModel = "gpt-4.1-mini"
        store.polishStrength = .strong

        let other = SettingsStore(defaults: defaults)
        other.remember(origin: "https://rc.example.com", username: "bob")
        checks.expect(!other.polishEnabled, "another account on the same gateway starts off")

        other.adopt(origin: "https://rc.example.com", username: "ada")
        checks.expect(other.polishEnabled, "and the first account's choices are still theirs")
        checks.equal(other.polishModel, "gpt-4.1-mini", "including the model")
        checks.equal(other.polishStrength, .strong, "and the strength")
        checks.equal(PolishStrength.allCases.map(\.rawValue), ["moderate", "strong"],
                     "the control offers two strengths, gentler first")
        defaults.removePersistentDomain(forName: "polish.checks")
    }

    // MARK: - A30, words another agent put in the conversation

    private static func agentMessages(_ checks: CheckRunner) {
        checks.noThrow("events/user_message.agent.json decodes as a message nobody typed") {
            guard let json = FixtureSource.json("events/user_message.agent.json") else {
                throw ProtocolFailure.malformed("events/user_message.agent.json")
            }
            let event = try json.decode(SessionEvent.self)
            guard case .userMessage(let payload) = event.body, payload.source == .agent else {
                throw ProtocolFailure.malformed("the fixture is a user message from an agent")
            }
            guard payload.text.hasPrefix("recon-ios:") else {
                throw ProtocolFailure.malformed("the text says who reported and what they said")
            }
        }
        checks.equal(EventSource.agent.rawValue, "agent", "the wire value is `agent`")
        checks.expect(EventSource.agent.isElsewhere,
                      "a turn another agent started is read as a turn nobody here started")
        checks.expect(EventSource.terminal.isElsewhere, "exactly as a terminal-started one is")
        checks.expect(!EventSource.remote.isElsewhere, "and a turn this app started is not")

        checks.noThrow("a turn_started with trigger agent decodes") {
            let payload = try JSONValue.object(["turn_id": .string("t"), "trigger": .string("agent")])
                .decode(TurnStartedPayload.self)
            guard payload.trigger == .agent, payload.trigger.isElsewhere else {
                throw ProtocolFailure.malformed("turn_started trigger")
            }
        }
        // A value no build has heard of must not crash an app mid-transcript.
        checks.noThrow("an unknown source decodes as itself") {
            let payload = try JSONValue.object(["text": .string("hi"), "source": .string("seance")])
                .decode(UserMessagePayload.self)
            guard payload.source.rawValue == "seance" else {
                throw ProtocolFailure.malformed("unknown source")
            }
        }

        let agentRows = DemoFixtures.sharedHistory().filter {
            if case .userMessage(let payload) = $0.body { return payload.source == .agent }
            return false
        }
        checks.equal(agentRows.count, 1, "the demo carries one message another agent filed")
    }

    // MARK: - A31, the comparison

    private static func versions(_ checks: CheckRunner) {
        checks.expect(AppVersion("1.2.3") < AppVersion("1.10.0"), "ten is a number, not a character")
        checks.expect(AppVersion("0.9.9") < AppVersion("1.0.0"), "a major version wins")
        checks.expect(AppVersion("1.2") == AppVersion("1.2.0"), "a missing part is zero")
        checks.expect(AppVersion("banana") == AppVersion("0.0.0"), "and so is anything unreadable")
        checks.equal(AppVersion("2.0.1").description, "2.0.1", "a version says itself back")

        let apps = AppsInfo(ios: AppSupport(minimumVersion: "1.0.0",
                                            updateURL: "https://testflight.apple.com/join/X"))
        checks.expect(AppUpdateRequirement.of(apps, current: "0.9.0") != nil,
                      "a build below the minimum has to update")
        checks.equal(AppUpdateRequirement.of(apps, current: "1.0.0"), nil,
                     "a build that meets it does not")
        checks.equal(AppUpdateRequirement.of(apps, current: "1.4.0"), nil, "and neither does a newer one")
        checks.equal(AppUpdateRequirement.of(nil, current: "0.0.1"), nil,
                     "a gateway that states no minimum asks for nothing")
        checks.equal(AppUpdateRequirement.of(AppsInfo(ios: nil), current: "0.0.1"), nil,
                     "and neither does one that names no app")
        checks.equal(AppUpdateRequirement.of(apps, current: "0.9.0")?.updateURL?.absoluteString,
                     "https://testflight.apple.com/join/X", "the link the operator named is kept")
        let insecure = AppsInfo(ios: AppSupport(minimumVersion: "1.0.0", updateURL: "itms://apps"))
        checks.equal(AppUpdateRequirement.of(insecure, current: "0.9.0")?.updateURL, nil,
                     "a link that is not https is not followed")

        checks.noThrow("http/health.response.json carries the minimum app build") {
            guard let json = FixtureSource.json("http/health.response.json") else {
                throw ProtocolFailure.malformed("http/health.response.json")
            }
            guard try json.decode(HealthResponse.self).apps?.ios?.minimumVersion == "0.1.0" else {
                throw ProtocolFailure.malformed("health apps")
            }
        }
        checks.noThrow("and so do the config response and the hello") {
            guard let config = FixtureSource.json("http/config.response.json"),
                  let hello = FixtureSource.json("app/hello.json"),
                  try config.decode(GatewayConfig.self).apps?.ios != nil,
                  try hello.decode(HelloFrame.self).apps?.ios != nil else {
                throw ProtocolFailure.malformed("config and hello apps")
            }
        }
    }

    // MARK: - A31, the rule the connection applies

    @MainActor
    private static func minimumVersion(_ checks: CheckRunner) async {
        // The demo asks for exactly this build, so nothing is blocked.
        let running = ConnectionStore(makeAPI: { _ in DemoGateway() }, makeChannel: { _ in DemoGateway() })
        let gateway = DemoGateway()
        await running.enterDemo(api: gateway, channel: gateway)
        await settle { running.hasSnapshot }
        checks.equal(running.updateRequired, nil, "a gateway this build meets blocks nothing")
        checks.expect(running.polish.enabled, "and the demo can polish a dictation")

        // A gateway that wants a newer build stops the app wherever it is.
        let demanding = DemoGateway(minimumAppVersion: DemoFixtures.laterAppVersion)
        let blocked = ConnectionStore(makeAPI: { _ in demanding }, makeChannel: { _ in demanding })
        await blocked.enterDemo(api: demanding, channel: demanding)
        await settle { blocked.updateRequired != nil }
        checks.equal(blocked.updateRequired?.minimum, AppVersion(DemoFixtures.laterAppVersion),
                     "the gateway's minimum is what the screen shows")
        checks.equal(blocked.updateRequired?.current, AppVersion(AppBuild.version),
                     "beside this build's own version")

        // `/api/health` answers before anyone has signed in, which is the point
        // of it: the sign-in form is blocked too.
        let unsigned = ConnectionStore(makeAPI: { _ in demanding }, makeChannel: { _ in demanding })
        _ = await unsigned.registrationOpen(origin: "https://rc.example.com")
        checks.expect(unsigned.updateRequired != nil,
                      "the public health route blocks the app before it has a credential")

        await blocked.signOut()
        checks.equal(blocked.updateRequired, nil,
                     "signing out is the way to another gateway, so it clears the screen")
    }

    @MainActor
    private static func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline { try? await Task.sleep(for: .milliseconds(10)) }
    }

    /// A channel that takes every message, so a send under test ends where a
    /// send ends and not in the refusal path that puts the words back.
    @MainActor
    final class AcceptingChannel: GatewayChannel {
        nonisolated let events: AsyncStream<GatewayEvent>

        init() { events = AsyncStream<GatewayEvent>.makeStream().stream }

        func connect() async {}
        func disconnect() async {}

        @discardableResult
        func request(_ request: GatewayRequest) async throws -> JSONValue {
            request.type == "session.send" ? .object(["accepted": .string("sent")]) : .object([:])
        }
    }
}
