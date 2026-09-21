import Testing
import Foundation
@testable import RCCore

/// `docs/DESIGN.md` § "The model card": on a session this app drives, every
/// change made from the card is drawn the moment it is made, and the device's
/// reply either confirms it or puts the previous value back with an error.
///
/// Amendment A40: not on a `shared` session. There the change is typed into
/// somebody else's terminal, so the card waits for the reply rather than
/// drawing ahead of it. These cover both halves.
@Suite("The model card draws a change it owns, and waits for one it types")
struct SessionSetTests {
    private func session(title: String = "T", effort: String? = "medium",
                         speed: String? = nil) -> Session {
        Session(sessionID: "s", deviceID: "d", agent: "codex", title: title, cwd: "/tmp",
                state: .idle, control: .remote, effort: effort, speed: speed)
    }

    /// The same session a terminal owns and the device is attached to, on the
    /// agent whose shim types the model and the effort in (A40).
    private func shared(title: String = "T", effort: String? = "medium") -> Session {
        Session(sessionID: "s", deviceID: "d", agent: "claude", title: title, cwd: "/tmp",
                state: .idle, origin: .terminal, control: .shared, effort: effort)
    }

    @Test("A speed change is visible before the channel answers")
    @MainActor
    func drawnBeforeTheReply() async {
        let channel = HeldSetChannel()
        let chat = ChatStore(session: session(), channel: channel)

        let setting = Task { await chat.set(speed: .tier("fast")) }
        await channel.waitForSet()
        #expect(chat.session.speed == "fast", "the lightning fills on the tap, not on the reply")

        channel.release(.success(SessionResult(session: session(effort: "high", speed: "fast"))))
        await setting.value
        #expect(chat.session.speed == "fast")
        #expect(chat.session.effort == "high", "and the device's own copy replaces it")
        #expect(chat.errorMessage == nil)
    }

    @Test("A refusal puts the previous value back, with the error")
    @MainActor
    func refusalRestores() async {
        let channel = HeldSetChannel()
        let chat = ChatStore(session: session(effort: "medium"), channel: channel)

        let setting = Task { await chat.set(effort: "high") }
        await channel.waitForSet()
        #expect(chat.session.effort == "high")

        channel.release(.failure(GatewayErrorBody(code: .unsupported, message: "Not on this agent.")))
        await setting.value
        #expect(chat.session.effort == "medium", "the level the session was on comes back")
        #expect(chat.errorMessage == "Not on this agent.")
    }

    @Test("A session that arrived while the request was in flight is not overwritten")
    @MainActor
    func newerSessionSurvivesARefusal() async {
        let channel = HeldSetChannel()
        let chat = ChatStore(session: session(effort: "medium"), channel: channel)

        let setting = Task { await chat.set(effort: "high") }
        await channel.waitForSet()

        // The device publishes a change of its own between the tap and the
        // refusal. Rolling back over it would put a stale level on screen.
        chat.receive(.sessionUpdated(session(title: "renamed at the terminal", effort: "low")))
        #expect(chat.session.effort == "low")

        channel.release(.failure(GatewayErrorBody(code: .unsupported, message: "Not on this agent.")))
        await setting.value
        #expect(chat.session.effort == "low", "the newer session stands")
        #expect(chat.session.title == "renamed at the terminal")
        #expect(chat.errorMessage == "Not on this agent.")
    }

    // MARK: - Amendment A40, a change that is typed into a terminal

    @Test("On a shared session the control waits instead of drawing the change")
    @MainActor
    func sharedSettingWaitsForTheReply() async {
        let channel = HeldSetChannel()
        let chat = ChatStore(session: shared(), channel: channel)
        chat.agent = DemoFixtures.claude

        let setting = Task { await chat.set(effort: "high") }
        await channel.waitForSet()
        #expect(chat.session.effort == "medium",
                "the card still reads the level the terminal is on")
        #expect(chat.pendingSettings == [.effort], "and names the control that is waiting")
        #expect(chat.isSettingPending)

        channel.release(.success(SessionResult(session: shared(effort: "high"))))
        await setting.value
        #expect(chat.session.effort == "high", "the reply is what the card follows")
        #expect(!chat.isSettingPending, "and the wait is over with it")
        #expect(chat.errorMessage == nil)
    }

    @Test("A busy terminal leaves the value alone and says so in the device's words")
    @MainActor
    func sharedConflictChangesNothing() async {
        let channel = HeldSetChannel()
        let chat = ChatStore(session: shared(), channel: channel)
        chat.agent = DemoFixtures.claude

        let setting = Task { await chat.set(model: "claude-opus-4-1") }
        await channel.waitForSet()
        #expect(chat.session.model == nil, "nothing was drawn to put back")

        channel.release(.failure(GatewayErrorBody(
            code: .conflict, message: "the terminal is busy; try again in a moment")))
        await setting.value
        #expect(chat.session.model == nil, "and the refusal leaves it exactly where it was")
        #expect(!chat.isSettingPending)
        #expect(chat.errorMessage == "the terminal is busy; try again in a moment")
    }

    /// The title is not typed into anything — it is the app's on every session
    /// — so a rename is still drawn the moment it is made.
    @Test("A rename on a shared session is still drawn at once")
    @MainActor
    func sharedTitleStaysOptimistic() async {
        let channel = HeldSetChannel()
        let chat = ChatStore(session: shared(), channel: channel)
        chat.agent = DemoFixtures.claude

        let setting = Task { await chat.set(title: "Release notes") }
        await channel.waitForSet()
        #expect(chat.session.title == "Release notes")
        #expect(!chat.isSettingPending, "nothing is being typed for a title")

        channel.release(.success(SessionResult(session: shared(title: "Release notes"))))
        await setting.value
        #expect(chat.errorMessage == nil)
    }
}

/// A channel that holds one `session.set` until the test says what the device
/// answered, so the moment between the tap and the reply can be looked at.
@MainActor
private final class HeldSetChannel: GatewayChannel {
    nonisolated let events: AsyncStream<GatewayEvent>
    private let continuation: AsyncStream<GatewayEvent>.Continuation
    private var held: CheckedContinuation<Result<SessionResult, any Error>, Never>?
    private var arrived: CheckedContinuation<Void, Never>?
    private var setPending = false

    init() {
        let stream = AsyncStream<GatewayEvent>.makeStream(bufferingPolicy: .bufferingOldest(8))
        events = stream.stream
        continuation = stream.continuation
    }

    func connect() async {}
    func disconnect() async {}

    @discardableResult
    func request(_ request: GatewayRequest) async throws -> JSONValue {
        guard request.type == "session.set" else { return .object([:]) }
        setPending = true
        arrived?.resume()
        arrived = nil
        let outcome = await withCheckedContinuation { (c: CheckedContinuation<Result<SessionResult, any Error>, Never>) in
            held = c
        }
        switch outcome {
        case .success(let result): return try JSONValue.encode(result)
        case .failure(let error): throw error
        }
    }

    /// Returns once the request has reached the channel and is waiting there.
    func waitForSet() async {
        guard !setPending else { setPending = false; return }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            if setPending { c.resume() } else { arrived = c }
        }
        setPending = false
    }

    func release(_ outcome: Result<SessionResult, any Error>) {
        held?.resume(returning: outcome)
        held = nil
    }
}
