import Testing
import Foundation
@testable import RCCore

/// Amendment A20: a question Claude Code asks in an attached session can be
/// answered from here. The card and the composer submit one set of answers, the
/// message field is the free-text answer to the question still waiting for one,
/// and a question answered in the terminal comes back saying so.
@Suite("Amendment A20, answering a question")
struct QuestionAnswerTests {
    private static let headline = QuestionItem(
        id: "q1", prompt: "What should the headline say?",
        options: [QuestionOption(id: "remote", label: "Remote control"),
                  QuestionOption(id: "phone", label: "From your phone")],
        allowText: true)
    private static let choices = QuestionItem(
        id: "q2", prompt: "Which sections?",
        options: [QuestionOption(id: "gateway", label: "Gateway"),
                  QuestionOption(id: "apps", label: "Apps")],
        multi: true)

    private func payload(_ questions: [QuestionItem]) -> QuestionPayload {
        QuestionPayload(requestID: "req-1", questions: questions)
    }

    @MainActor
    private func store(control: SessionControl = .shared,
                       question: QuestionPayload? = nil) throws -> (ChatStore, AnswerChannel) {
        let session = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T",
                              cwd: "/tmp", state: question == nil ? .idle : .needsInput,
                              control: control)
        let channel = AnswerChannel()
        let chat = ChatStore(session: session, channel: channel)
        chat.agent = DemoFixtures.claude
        if let question {
            chat.receive(.sessionEvent(sessionID: "s", deviceID: "d",
                                       event: SessionEvent(seq: 1, ts: 1,
                                                           kind: SessionEvent.questionKind,
                                                           blockID: "q-1",
                                                           body: .question(question))))
        }
        return (chat, channel)
    }

    // MARK: - Decoding

    @Test("A resolved question says who answered it, and an older one says nothing")
    func attributionDecodes() throws {
        let json: JSONValue = ["seq": 1, "ts": 1, "kind": "question", "block_id": "q",
                               "request_id": "r", "status": "resolved",
                               "answers": ["q1": ["clamp"]], "by": "terminal"]
        let event = try json.decode(SessionEvent.self)
        #expect(event.question?.by == .terminal)
        #expect(try JSONValue.encode(event)["by"]?.stringValue == "terminal")

        let quiet: JSONValue = ["seq": 1, "ts": 1, "kind": "question", "block_id": "q",
                                "request_id": "r", "status": "resolved"]
        #expect(try quiet.decode(SessionEvent.self).question?.by == nil)
    }

    // MARK: - What the card holds

    @Test("The composer's draft answers the first question nothing was chosen for")
    func draftFillsTheFirstUnansweredQuestion() {
        var draft = QuestionDraft(requestID: "req-1")
        let questions = [Self.choices, Self.headline]
        draft.toggle("gateway", of: Self.choices)
        #expect(draft.firstUnanswered(in: questions)?.id == "q1")

        let answers = draft.answers(for: questions, composing: "  Remote control for terminals  ")
        #expect(answers?["q2"] == .options(["gateway"]))
        #expect(answers?["q1"] == .text("Remote control for terminals"))
    }

    @Test("A draft with nowhere to go is left in the field")
    func draftIsKeptWhenItCannotBeAnswered() {
        var draft = QuestionDraft(requestID: "req-1")
        // The one question waiting takes options and no words.
        #expect(draft.answers(for: [Self.choices], composing: "something") == nil)
        // Nothing typed is nothing to submit.
        #expect(draft.answers(for: [Self.headline], composing: "   ") == nil)
        // Every question already answered on the card: the card's own Submit.
        draft.toggle("remote", of: Self.headline)
        #expect(draft.answers(for: [Self.headline], composing: "and something else") == nil)
    }

    /// The composer's draft is written to disk and restored on the next launch,
    /// so a value the card promises is neither stored nor logged cannot be
    /// typed there. Its own masked field is the only way in.
    @Test("A secret question is never answered from the message field")
    func secretsStayOffTheDraft() {
        let secret = QuestionItem(id: "q1", prompt: "Passphrase?", allowText: true, secret: true)
        let draft = QuestionDraft(requestID: "req-1")
        #expect(draft.firstUnanswered(in: [secret])?.id == "q1")
        #expect(draft.answers(for: [secret], composing: "hunter2") == nil)
    }

    @Test("A single-choice question keeps one option and a multi-select keeps several")
    func selectionRules() {
        var draft = QuestionDraft(requestID: "req-1")
        draft.toggle("remote", of: Self.headline)
        draft.toggle("phone", of: Self.headline)
        #expect(draft.answers(for: [Self.headline])["q1"] == .options(["phone"]))
        draft.toggle("phone", of: Self.headline)
        #expect(draft.answers(for: [Self.headline]).isEmpty, "choosing it again clears it")

        draft.toggle("apps", of: Self.choices)
        draft.toggle("gateway", of: Self.choices)
        #expect(draft.answers(for: [Self.choices])["q2"] == .options(["gateway", "apps"]),
                "in the order the question offered them, not the order they were tapped")
    }

    // MARK: - The composer

    @Test("A pending question turns the composer into an answer")
    @MainActor
    func composerAnswersWhileAQuestionIsPending() throws {
        let (chat, _) = try store(question: payload([Self.headline]))
        #expect(chat.pendingQuestion?.requestID == "req-1")
        #expect(chat.statusLine == "Waiting for your answer")
        #expect(chat.allowsAnswers, "an attached session takes the answer (A20)")
    }

    @Test("Submitting the draft answers the question and clears the field")
    @MainActor
    func submittingTheDraft() async throws {
        let (chat, channel) = try store(question: payload([Self.choices, Self.headline]))
        chat.choose("apps", of: Self.choices, in: payload([Self.choices, Self.headline]))
        chat.draft = "Remote control for your terminal agents"
        await chat.answerDraft()

        #expect(channel.requests.map(\.type) == ["session.answer"])
        let body = channel.requests.first?.body
        #expect(body?["request_id"]?.stringValue == "req-1")
        let answers = try #require(body?["answers"]).decodeAnswers()
        #expect(answers["q2"] == .options(["apps"]))
        #expect(answers["q1"] == .text("Remote control for your terminal agents"))
        #expect(chat.draft == "")
        #expect(chat.timeline.roots.allSatisfy { $0.pending == nil },
                "no optimistic row: an answer is not a message")
    }

    @Test("A draft that answers nothing is neither sent nor lost")
    @MainActor
    func aDraftWithNowhereToGoStays() async throws {
        let (chat, channel) = try store(question: payload([Self.choices]))
        chat.draft = "the headline"
        await chat.answerDraft()
        #expect(channel.requests.isEmpty, "nothing is submitted")
        #expect(chat.draft == "the headline", "and the words stay in the field")
    }

    @Test("With no question pending the composer is an ordinary composer")
    @MainActor
    func noQuestionNoAnswer() async throws {
        let (chat, channel) = try store()
        #expect(chat.pendingQuestion == nil)
        #expect(chat.statusLine == nil)
        chat.draft = "hello"
        await chat.answerDraft()
        #expect(channel.requests.isEmpty)
        #expect(chat.draft == "hello")
    }

    @Test("A session the terminal holds outright answers nothing from here")
    @MainActor
    func terminalSessionsTakeNoAnswer() throws {
        let (chat, _) = try store(control: .terminal, question: payload([Self.headline]))
        #expect(!chat.allowsAnswers)
        #expect(chat.pendingQuestion == nil)
    }

    @Test("A resolved question is no longer the pending one")
    @MainActor
    func resolvingClearsTheComposer() throws {
        let (chat, _) = try store(question: payload([Self.headline]))
        chat.receive(.sessionEvent(sessionID: "s", deviceID: "d",
                                   event: SessionEvent(seq: 2, ts: 2,
                                                       kind: SessionEvent.questionKind,
                                                       blockID: "q-1",
                                                       body: .question(QuestionPayload(
                                                        requestID: "req-1",
                                                        questions: [Self.headline],
                                                        status: .resolved,
                                                        answers: ["q1": .options(["remote"])],
                                                        by: .terminal)))))
        #expect(chat.pendingQuestion == nil)
        #expect(chat.timeline.entry(id: "q-1")?.question?.by == .terminal)
        #expect(chat.statusLine == nil)
    }

    // MARK: - The demo

    @Test("The demo's attached session carries a question the terminal answered")
    @MainActor
    func demoCarriesATerminalAnswer() {
        let answered = DemoFixtures.sharedHistory().compactMap(\.question).first { $0.by != nil }
        #expect(answered?.by == .terminal)
        #expect(answered?.status == .resolved)
        #expect(DemoFixtures.sharedQuestion.status == .pending)
        #expect(DemoFixtures.sharedQuestion.questions.first?.allowText == true,
                "so the composer's draft has somewhere to go")
    }
}

private extension JSONValue {
    func decodeAnswers() -> [String: QuestionAnswer] {
        (try? decode([String: QuestionAnswer].self)) ?? [:]
    }
}

/// A channel that records what was asked of it and agrees to everything.
@MainActor
private final class AnswerChannel: GatewayChannel {
    nonisolated let events: AsyncStream<GatewayEvent>
    private(set) var requests: [GatewayRequest] = []

    init() { events = AsyncStream<GatewayEvent>.makeStream(bufferingPolicy: .bufferingOldest(8)).stream }

    func connect() async {}
    func disconnect() async {}

    @discardableResult
    func request(_ request: GatewayRequest) async throws -> JSONValue {
        requests.append(request)
        return .object([:])
    }
}
