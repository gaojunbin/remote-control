import Testing
import Foundation
@testable import RCCore

/// `docs/DESIGN.md` § "The model card": every change made from the card is
/// drawn the moment it is made, and the device's reply either confirms it or
/// puts the previous value back with an error. These cover the three outcomes
/// a `session.set` can have while the card is still open.
@Suite("The model card is drawn at once")
struct SessionSetTests {
    private func session(title: String = "T", effort: String? = "medium",
                         speed: String? = nil) -> Session {
        Session(sessionID: "s", deviceID: "d", agent: "codex", title: title, cwd: "/tmp",
                state: .idle, control: .remote, effort: effort, speed: speed)
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
