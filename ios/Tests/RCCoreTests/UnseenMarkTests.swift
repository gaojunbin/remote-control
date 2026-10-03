import Testing
import Foundation
@testable import RCCore

/// Amendment A47 — a session that stopped working and waits for you is marked
/// until someone looks.
///
/// The fixtures the amendment added, the rule the gateway keeps the mark by
/// (which the demo keeps it by too), the count the app icon carries, and when
/// the app tells the gateway the person has a conversation in front of them.
@Suite("Amendment A47, the red dot and the badge")
struct UnseenMarkTests {
    // MARK: - The wire

    @Test("A session carries the gateway's mark, and one without the field carries none")
    func sessionDecodes() throws {
        let sessions = try #require(try fixture("app/hello.json")["sessions"]).decode([Session].self)
        #expect(sessions.count == 2)
        #expect(sessions[0].unseen)
        #expect(!sessions[1].unseen, "absent is false: a gateway before A47 sends no field")
    }

    @Test("session.seen is the fixture's shape, under a request id of its own")
    func seenRequest() throws {
        let expected = try #require(try fixture("app/session.seen.json").objectValue)
        let sessionID = try #require(expected["session_id"]?.stringValue)
        let built = try #require(GatewayRequest.seen(sessionID: sessionID).json.objectValue)
        #expect(built["type"] == expected["type"])
        #expect(built["session_id"] == expected["session_id"])
        #expect(built["id"]?.stringValue?.isEmpty == false)
        #expect(Set(built.keys) == Set(expected.keys), "and nothing the fixture does not carry")
    }

    @Test("Every push carries the count, and the badge-only push says what it is")
    func pushCount() throws {
        let badge = try #require(try fixture("http/push.payload.badge.json")["rc"]).decode(PushRoute.self)
        #expect(badge.kind == .badge)
        #expect(badge.badge == 0)
        #expect(badge.title.isEmpty, "it shows nothing")
        let alert = try #require(try fixture("http/push.payload.json")["rc"]).decode(PushRoute.self)
        #expect(alert.kind == .needsApproval)
        #expect(alert.badge == 1)
        let older = try #require(try fixture("http/push.payload.limit.json")["rc"]).decode(PushRoute.self)
        #expect(older.badge == nil, "a payload without the count says nothing about it")
    }

    @Test("The mark is kept in the cache the list paints from")
    func cacheKeepsTheMark() async throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "rc-unseen-\(UUID().uuidString)", directoryHint: .isDirectory)
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = LocalCache(directory: directory)
        await cache.save(CachedWorkspace(sessions: [session("a", unseen: true), session("b")]),
                         origin: "https://rc.example.invalid", username: "me")
        let loaded = try #require(await cache.load(origin: "https://rc.example.invalid", username: "me"))
        #expect(loaded.sessions.map(\.unseen) == [true, false])
    }

    // MARK: - The rule

    private static let waiting: [(SessionState, SessionControl)] = [
        (.needsApproval, .remote), (.needsInput, .shared), (.idle, .remote), (.idle, .shared),
        (.idle, .terminal), (.readonly, .terminal)
    ]

    @Test("A running turn that becomes waiting marks the session")
    func runningToWaiting() {
        for (state, control) in Self.waiting {
            let next = UnseenMark.next(previous: session("s", state: .running, control: control),
                                       current: session("s", state: state, control: control))
            #expect(next, "running → \(state) with control \(control)")
        }
    }

    @Test("A session that only started has done nothing to look at, so it brings no mark")
    func startingBringsNone() {
        for (state, control) in Self.waiting {
            let next = UnseenMark.next(previous: session("s", state: .starting, control: control),
                                       current: session("s", state: state, control: control))
            #expect(!next, "starting → \(state) with control \(control)")
        }
        #expect(!UnseenMark.works(.starting), "green, but not a turn under way")
    }

    @Test("Grey and red dots never bring one: an exited CLI and an error wait for nobody")
    func notWaiting() {
        let running = session("s", state: .running)
        for current in [session("s", state: .idle, control: .none), session("s", state: .readonly, control: .none),
                        session("s", state: .error), session("s", state: .stopped)] {
            #expect(!UnseenMark.next(previous: running, current: current), "\(current.state), \(current.control)")
        }
    }

    @Test("Waiting to waiting keeps whatever the session carried")
    func waitingToWaiting() {
        for carried in [true, false] {
            let asking = session("s", state: .needsApproval, unseen: carried)
            #expect(UnseenMark.next(previous: asking, current: session("s", state: .idle)) == carried)
            let idle = session("s", state: .idle, unseen: carried)
            #expect(UnseenMark.next(previous: idle, current: session("s", state: .needsInput)) == carried)
        }
    }

    @Test("A turn running again clears it: someone carried on elsewhere")
    func workingAgain() {
        let marked = session("s", state: .idle, unseen: true)
        #expect(!UnseenMark.next(previous: marked, current: session("s", state: .running)))
        #expect(UnseenMark.next(previous: marked, current: session("s", state: .starting)),
                "a process starting is not yet a turn, so the mark waits for one")
    }

    @Test("Archiving clears it, and an archived session is never marked")
    func archiving() {
        let marked = session("s", state: .idle, unseen: true)
        #expect(!UnseenMark.next(previous: marked, current: session("s", state: .stopped, control: .none,
                                                                     archived: true)))
        #expect(!UnseenMark.next(previous: session("s", state: .running),
                                 current: session("s", state: .idle, archived: true)))
    }

    @Test("The states alone decide: a turn that ended offline still marks the session")
    func onlineIgnored() {
        let ended = session("s", state: .idle)
        #expect(ended.dotTone(online: false) == .off, "the dot is grey while the machine is gone")
        #expect(UnseenMark.next(previous: session("s", state: .running), current: ended),
                "and the turn it finished is still one to look at")
    }

    @Test("The badge counts the unarchived sessions with a red dot")
    func count() {
        let sessions = [session("a", unseen: true), session("b", unseen: true, archived: true),
                        session("c"), session("d", state: .needsApproval, unseen: true)]
        #expect(UnseenMark.count(in: sessions) == 2)
        #expect(UnseenMark.count(in: []) == 0)
        #expect(UnseenMark.count(in: sessions, excluding: "d/a") == 1,
                "the conversation in front is being looked at, so it is not counted")
        #expect(UnseenMark.count(in: sessions, excluding: "d/c") == 2, "and a quiet one changes nothing")
    }

    // MARK: - Telling the gateway

    @Test("session.seen goes only while the copy carries the mark, and once for it")
    @MainActor
    func sendsOnlyWhileMarked() async throws {
        let channel = SeenChannel(sessions: [session("marked", unseen: true), session("quiet")])
        let connection = try await connected(to: channel)
        #expect(connection.unseenCount == 1)

        await connection.markSeen(deviceID: "d", sessionID: "quiet")
        #expect(channel.seen.isEmpty, "a session without the mark asks for nothing")

        await connection.markSeen(deviceID: "d", sessionID: "marked")
        await connection.markSeen(deviceID: "d", sessionID: "marked")
        #expect(channel.seen == ["marked"], "one request for one mark, however often it is found")

        channel.emit(.sessionUpdated(session("marked")))
        try await settle { connection.unseenCount == 0 }
        await connection.markSeen(deviceID: "d", sessionID: "marked")
        #expect(channel.seen == ["marked"], "the gateway took it off, so there is nothing to say")

        channel.emit(.sessionUpdated(session("marked", unseen: true)))
        try await settle { connection.unseenCount == 1 }
        await connection.markSeen(deviceID: "d", sessionID: "marked")
        #expect(channel.seen == ["marked", "marked"], "a turn that ended again is a new mark")
    }

    @Test("A failed session.seen says nothing, and the next occasion sends it again")
    @MainActor
    func failureIsSilent() async throws {
        let channel = SeenChannel(sessions: [session("marked", unseen: true)])
        let connection = try await connected(to: channel)
        channel.failsSeen = true
        await connection.markSeen(deviceID: "d", sessionID: "marked")
        #expect(channel.seen == ["marked"])
        #expect(connection.errorMessage == nil, "no banner for a request that is idempotent")
        channel.failsSeen = false
        await connection.markSeen(deviceID: "d", sessionID: "marked")
        #expect(channel.seen == ["marked", "marked"])
    }

    @Test("The conversation in front with the mark on is reported at every moment it becomes so")
    @MainActor
    func reporterFollowsTheFront() async throws {
        let channel = SeenChannel(sessions: [session("a", unseen: true), session("b"), session("c")])
        let connection = try await connected(to: channel)
        let front = Front()
        let reporter = SeenReporter(connection: connection) { front.key }
        reporter.start()

        try await Task.sleep(for: .milliseconds(100))
        #expect(channel.seen.isEmpty, "nothing is in front, so nothing has been seen")

        front.key = "d/a"
        try await settle { channel.seen == ["a"] }
        #expect(channel.seen == ["a"], "opening it in front of the person sends it")

        front.key = "d/b"
        channel.emit(.sessionUpdated(session("c", unseen: true)))
        try await Task.sleep(for: .milliseconds(150))
        #expect(channel.seen == ["a"], "a quiet conversation, and a mark on one behind it, send nothing")

        channel.emit(.sessionUpdated(session("b", unseen: true)))
        try await settle { channel.seen == ["a", "b"] }
        #expect(channel.seen == ["a", "b"], "a mark arriving on the conversation in front is cleared at once")

        front.key = nil
        front.key = "d/c"
        try await settle { channel.seen == ["a", "b", "c"] }
        #expect(channel.seen == ["a", "b", "c"], "and so is one in front when its window comes forward")
    }

    // MARK: - The demo keeps the mark as the gateway does

    @Test("The demo starts with one session marked, so the list and the badge show it")
    @MainActor
    func demoStartsWithOneMark() async throws {
        let marked = DemoFixtures.sessions.filter(\.unseen)
        #expect(marked.map(\.sessionID) == [DemoFixtures.approvalSessionID])
        let gateway = DemoGateway(resumeDelay: nil)
        let connection = ConnectionStore()
        await connection.enterDemo(api: gateway, channel: gateway)
        try await settle { connection.hasSnapshot }
        #expect(connection.unseenCount == 1)
    }

    @Test("A scripted turn that ends marks the session, and session.seen takes it off once")
    @MainActor
    func demoMarksAndClears() async throws {
        let gateway = DemoGateway(echoDelay: .zero, resumeDelay: nil)
        let connection = ConnectionStore()
        let published = Tally()
        connection.onSessionTransition = { _, current in
            if current.sessionID == DemoFixtures.piSessionID { published.count += 1 }
        }
        await connection.enterDemo(api: gateway, channel: gateway)
        try await settle { connection.hasSnapshot }
        let pi = { connection.sessions.first { $0.sessionID == DemoFixtures.piSessionID } }

        _ = try await gateway.request(.send(sessionID: DemoFixtures.piSessionID, text: "go"))
        try await settle { pi()?.state == .running }
        #expect(pi()?.unseen == false, "working is never marked")
        try await settle { pi()?.state == .idle }
        try await settle { pi()?.unseen == true }
        #expect(pi()?.unseen == true, "the turn ended and nobody was looking")
        #expect(connection.unseenCount == 2)

        let before = published.count
        await connection.markSeen(deviceID: DemoFixtures.macDeviceID, sessionID: DemoFixtures.piSessionID)
        try await settle { pi()?.unseen == false }
        #expect(pi()?.unseen == false, "seen takes the mark off")
        #expect(published.count == before + 1, "in one session.updated")
        _ = try await gateway.request(.seen(sessionID: DemoFixtures.piSessionID))
        try await Task.sleep(for: .milliseconds(100))
        #expect(published.count == before + 1,
                "and a second seen publishes nothing: there was nothing to take off")
    }

    @Test("The demo clears the mark when the session works again and when it is closed")
    @MainActor
    func demoClears() async throws {
        let gateway = DemoGateway(echoDelay: .zero, resumeDelay: nil)
        let connection = ConnectionStore()
        await connection.enterDemo(api: gateway, channel: gateway)
        try await settle { connection.hasSnapshot }
        let vite = { connection.sessions.first { $0.sessionID == DemoFixtures.approvalSessionID } }
        let marked = try #require(vite())
        #expect(marked.unseen)

        // Approving from somewhere else is carrying on, which takes the dot away.
        _ = try await gateway.request(.approve(sessionID: marked.sessionID, requestID: "demo-approval-1",
                                               optionID: "approved"))
        try await settle { vite()?.state == .running }
        #expect(vite()?.unseen == false)

        let pi = { connection.sessions.first { $0.sessionID == DemoFixtures.piSessionID } }
        _ = try await gateway.request(.send(sessionID: DemoFixtures.piSessionID, text: "go"))
        try await settle { pi()?.unseen == true }
        await connection.close(session: try #require(pi()))
        #expect(pi()?.archived == true)
        #expect(pi()?.unseen == false, "a closed session is filed without its dot")
    }

    @Test("The demo answers not_found for a session it does not have")
    func demoUnknownSession() async throws {
        let gateway = DemoGateway(resumeDelay: nil)
        do {
            _ = try await gateway.request(.seen(sessionID: "no-such-session"))
            Issue.record("an unknown session was seen")
        } catch let refusal as GatewayErrorBody {
            #expect(refusal.code == .notFound)
        }
    }

    // MARK: - Helpers

    private func session(_ id: String, state: SessionState = .idle, control: SessionControl = .remote,
                         unseen: Bool = false, archived: Bool = false) -> Session {
        Session(sessionID: id, deviceID: "d", agent: "claude", title: id, cwd: "/src",
                state: state, control: control, archived: archived, unseen: unseen)
    }

    @MainActor
    private func connected(to channel: SeenChannel) async throws -> ConnectionStore {
        let connection = ConnectionStore()
        await connection.enterDemo(api: DemoGateway(resumeDelay: nil), channel: channel)
        try await settle { connection.hasSnapshot }
        return connection
    }

    @MainActor
    private func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
    }

    private static let fixtures = URL(filePath: #filePath)
        .deletingLastPathComponent()   // ios/Tests/RCCoreTests
        .deletingLastPathComponent()   // ios/Tests
        .deletingLastPathComponent()   // ios
        .deletingLastPathComponent()   // repository root
        .appending(path: "protocol/fixtures", directoryHint: .isDirectory)

    private func fixture(_ relativePath: String) throws -> JSONValue {
        let data = try Data(contentsOf: Self.fixtures.appending(path: relativePath))
        return try JSONDecoder().decode(JSONValue.self, from: data)
    }
}

/// How many times something happened, counted on the main actor.
@MainActor
private final class Tally {
    var count = 0
}

/// What a test says is in front of the person, observed as an app model is.
@MainActor
@Observable
private final class Front {
    var key: String?
}

/// A channel that serves one hello of the test's sessions, emits frames on
/// command, answers everything with `{}`, and remembers every `session.seen`.
@MainActor
private final class SeenChannel: GatewayChannel {
    nonisolated let events: AsyncStream<GatewayEvent>
    private let continuation: AsyncStream<GatewayEvent>.Continuation
    private let sessions: [Session]
    private(set) var seen: [String] = []
    var failsSeen = false

    init(sessions: [Session]) {
        self.sessions = sessions
        let stream = AsyncStream<GatewayEvent>.makeStream(bufferingPolicy: .bufferingOldest(64))
        events = stream.stream
        continuation = stream.continuation
    }

    func connect() async {
        continuation.yield(.state(.connected))
        continuation.yield(.frame(.hello(HelloFrame(
            protocolVersion: RemoteProtocol.version, gatewayVersion: "test",
            user: UserIdentity(username: "me"), devices: [], sessions: sessions,
            stt: .disabled, serverTime: 0))))
    }

    func disconnect() async { continuation.yield(.state(.disconnected)) }

    func emit(_ frame: AppFrame) { continuation.yield(.frame(frame)) }

    func request(_ request: GatewayRequest) async throws -> JSONValue {
        guard request.type == "session.seen" else { return .object([:]) }
        seen.append(request.body["session_id"]?.stringValue ?? "")
        if failsSeen { throw TransportError.deliveryUncertain }
        return .object([:])
    }
}
