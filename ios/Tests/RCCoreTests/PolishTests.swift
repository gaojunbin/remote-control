import Testing
import Foundation
@testable import RCCore

/// Amendment A29 — the dictated span is what is polished, and nothing else.
@Suite("Dictation polish (A29)")
struct DictationPolishTests {
    private let span = DictationSpan(base: "Two things:", dictated: "um fix the the dot")

    @Test("Only the dictated words are replaced; typed words are left alone")
    func replacesTheSpan() {
        #expect(span.dictatedDraft == "Two things:\num fix the the dot")
        #expect(DictationPolish.applyPolished(current: span.dictatedDraft, span: span,
                                              polished: " Fix the dot. ")
                == "Two things:\nFix the dot.")
    }

    @Test("A field the person has moved on from keeps what they put in it")
    func leavesAnEditedFieldAlone() {
        #expect(DictationPolish.applyPolished(current: "something else", span: span,
                                              polished: "Fix the dot.") == nil)
        #expect(DictationPolish.applyPolished(current: span.dictatedDraft, span: span,
                                              polished: "  ") == nil)
    }

    @Test("Undo restores the words exactly as they were dictated")
    func undo() {
        let polished = span.polishedDraft("Fix the dot.")
        #expect(DictationPolish.undoPolished(current: polished, span: span,
                                             polished: "Fix the dot.") == span.dictatedDraft)
        #expect(DictationPolish.undoPolished(current: "typed over", span: span,
                                             polished: "Fix the dot.") == nil)
    }

    @Test("Nothing empty and nothing over the contract's limit is sent")
    func limits() {
        #expect(DictationPolish.canPolish("um so"))
        #expect(!DictationPolish.canPolish("\n  \n"))
        #expect(!DictationPolish.canPolish(String(repeating: "x", count: 8193)))
    }

    @MainActor
    @Test("The model is given the last twenty messages, oldest first, each trimmed")
    func context() {
        var timeline = Timeline()
        var seq = 0
        for index in 1...30 {
            seq += 1
            timeline.apply(SessionEvent(seq: seq, ts: 0, kind: SessionEvent.userMessageKind,
                                        blockID: "u\(index)",
                                        body: .userMessage(UserMessagePayload(text: "ask \(index)"))))
        }
        seq += 1
        timeline.apply(SessionEvent(seq: seq, ts: 0, kind: SessionEvent.assistantTextKind,
                                    blockID: "a",
                                    body: .assistantText(StreamTextPayload(
                                        text: String(repeating: "y", count: 4500), done: true))))
        let items = DictationPolish.context(timeline)
        #expect(items.count == 20)
        #expect(items.first?.text == "ask 12")
        #expect(items.last?.role == .assistant)
        #expect(items.last?.text.count == 4000)
    }

    @Test("An automatic dictation language is no hint, and a chosen one is")
    func languageHint() {
        #expect(DictationPolish.request(span: span, model: "m", strength: .strong,
                                        language: "auto", context: []).language == nil)
        #expect(DictationPolish.request(span: span, model: "m", strength: .strong,
                                        language: "zh", context: []).language == "zh")
    }
}

/// The composer's half of A29, driven through `ChatStore` with a fake model.
@MainActor
@Suite("Dictation polish, the flow (A29)")
struct DictationPolishFlowTests {
    private let span = DictationSpan(base: "", dictated: "um fix the the dot")

    private func store() -> ChatStore {
        let chat = ChatStore(session: DemoFixtures.sessions[0], channel: AcceptingChannel())
        chat.deviceOnline = true
        chat.draft = span.dictatedDraft
        return chat
    }

    @Test("The answer lands in the field and leaves Undo behind it")
    func success() async {
        let chat = store()
        chat.polishService = { _ in "Fix the dot." }
        chat.polish(span: span, model: "gpt-4.1-mini", strength: .moderate, language: "en")
        #expect(chat.statusLine == "Polishing…")
        await settle { chat.polishPhase != .polishing }
        #expect(chat.draft == "Fix the dot.")
        chat.undoPolish()
        #expect(chat.draft == span.dictatedDraft)
        #expect(chat.polishPhase == .idle)
    }

    @Test("A failure says so in one line and changes not one word")
    func failure() async {
        let chat = store()
        chat.polishService = { _ in throw TransportError.notConnected }
        chat.polish(span: span, model: "m", strength: .strong, language: "en")
        await settle { chat.polishPhase != .polishing }
        #expect(chat.polishPhase == .failed)
        #expect(chat.draft == span.dictatedDraft)
        chat.clearPolishNote()
        #expect(chat.polishPhase == .idle)
    }

    @Test("A send drops a request still out, and what goes is what was in the field")
    func sendWins() async {
        let chat = store()
        chat.polishService = { _ in
            try? await Task.sleep(for: .milliseconds(200))
            return "Fix the dot."
        }
        chat.polish(span: span, model: "m", strength: .moderate, language: "en")
        await chat.send()
        #expect(chat.polishPhase == .idle)
        #expect(chat.draft.isEmpty)
        try? await Task.sleep(for: .milliseconds(350))
        #expect(chat.draft.isEmpty)
    }

    /// `docs/DESIGN.md` § "The composer": typing into the field while the
    /// spinner is up ends the wait. The person's words win, so the request is
    /// dropped and the answer that arrives afterwards is never applied.
    @Test("An edit while the request is out drops it, and the late answer never lands")
    func editWhilePolishing() async {
        let chat = store()
        chat.polishService = { _ in
            try? await Task.sleep(for: .milliseconds(200))
            return "Fix the dot."
        }
        chat.polish(span: span, model: "m", strength: .moderate, language: "en")
        #expect(chat.polishPhase == .polishing)

        chat.draft += " and the spinner"
        let typed = chat.draft
        #expect(chat.polishPhase == .idle, "the request is dropped the moment they type")
        try? await Task.sleep(for: .milliseconds(400))
        #expect(chat.draft == typed, "and the answer that was already out is never applied")
        #expect(chat.polishPhase == .idle)
    }

    @Test("The note goes on the next edit")
    func editEndsTheNote() async {
        let chat = store()
        chat.polishService = { _ in "Fix the dot." }
        chat.polish(span: span, model: "m", strength: .moderate, language: "en")
        await settle { chat.polishPhase != .polishing }
        chat.draft += " now"
        #expect(chat.polishPhase == .idle)
    }

    private func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline { try? await Task.sleep(for: .milliseconds(10)) }
    }

    /// A channel that takes every message, so a send under test ends where a
    /// send ends rather than in the refusal path.
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

/// Amendment A31 — the version comparison, and the rule built on it.
@Suite("The oldest app build a gateway supports (A31)")
struct AppVersionTests {
    @Test("Versions compare part by part, not as text")
    func ordering() {
        #expect(AppVersion("1.2.3") < AppVersion("1.10.0"))
        #expect(AppVersion("0.9.0") < AppVersion("1.0.0"))
        #expect(AppVersion("2.0.0") > AppVersion("1.99.99"))
        #expect(AppVersion("1.2") == AppVersion("1.2.0"))
        #expect(AppVersion("") == AppVersion("0.0.0"))
        #expect(AppVersion("not a version") == AppVersion("0.0.0"))
    }

    @Test("Only a build below the minimum has to update")
    func rule() {
        let apps = AppsInfo(ios: AppSupport(minimumVersion: "1.2.0",
                                            updateURL: "https://testflight.apple.com/join/X"))
        #expect(AppUpdateRequirement.of(apps, current: "1.1.9") != nil)
        #expect(AppUpdateRequirement.of(apps, current: "1.2.0") == nil)
        #expect(AppUpdateRequirement.of(apps, current: "2.0.0") == nil)
        #expect(AppUpdateRequirement.of(nil, current: "0.0.1") == nil)
        #expect(AppUpdateRequirement.of(AppsInfo(ios: nil), current: "0.0.1") == nil)
    }

    @Test("Only an https link is offered to the person")
    func link() {
        let secure = AppsInfo(ios: AppSupport(minimumVersion: "9.0.0",
                                              updateURL: "https://apps.apple.com/app/id1"))
        #expect(AppUpdateRequirement.of(secure, current: "1.0.0")?.updateURL != nil)
        let other = AppsInfo(ios: AppSupport(minimumVersion: "9.0.0", updateURL: "itms://apps"))
        #expect(AppUpdateRequirement.of(other, current: "1.0.0")?.updateURL == nil)
        let none = AppsInfo(ios: AppSupport(minimumVersion: "9.0.0"))
        #expect(AppUpdateRequirement.of(none, current: "1.0.0")?.updateURL == nil)
    }

    @MainActor
    @Test("The first source to say the build is too old wins, and signing out clears it")
    func connectionRule() async {
        let demanding = DemoGateway(minimumAppVersion: DemoFixtures.laterAppVersion)
        let store = ConnectionStore(makeAPI: { _ in demanding }, makeChannel: { _ in demanding })
        await store.enterDemo(api: demanding, channel: demanding)
        let deadline = Date().addingTimeInterval(3)
        while store.updateRequired == nil, Date() < deadline {
            try? await Task.sleep(for: .milliseconds(10))
        }
        #expect(store.updateRequired?.minimum == AppVersion(DemoFixtures.laterAppVersion))
        await store.signOut()
        #expect(store.updateRequired == nil)
    }
}

/// Amendment A30 — words another agent put into a Claude conversation.
@Suite("Messages from other agents (A30)")
struct AgentMessageTests {
    @Test("`agent` decodes as itself and reads like a turn nobody here started")
    func source() throws {
        let payload = try JSONValue.object(["text": .string("recon-ios: done"),
                                            "source": .string("agent")])
            .decode(UserMessagePayload.self)
        #expect(payload.source == .agent)
        #expect(payload.source.isElsewhere)
        #expect(EventSource.terminal.isElsewhere)
        #expect(!EventSource.remote.isElsewhere)
        #expect(!EventSource.queue.isElsewhere)
    }

    @Test("A turn another agent's message started carries the trigger")
    func trigger() throws {
        let payload = try JSONValue.object(["turn_id": .string("t"), "trigger": .string("agent")])
            .decode(TurnStartedPayload.self)
        #expect(payload.trigger == .agent)
        #expect(payload.trigger.isElsewhere)
    }

    @Test("The demo's attached Claude session carries one such message")
    func demo() {
        let rows = DemoFixtures.sharedHistory().compactMap { event -> UserMessagePayload? in
            guard case .userMessage(let payload) = event.body else { return nil }
            return payload.source == .agent ? payload : nil
        }
        #expect(rows.count == 1)
        #expect(rows.first?.text.hasPrefix("recon-ios:") == true)
    }
}
