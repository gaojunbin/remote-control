import Foundation
import RCCore

/// Amendment A47: the red dot of a session that stopped working and waits for
/// the person, the badge that counts them, and `session.seen` — the fixtures,
/// the gateway's rule as the demo keeps it, and the store's one request per
/// mark.
enum UnseenChecks {
    @MainActor
    static func run() async -> CheckResult {
        let checks = CheckRunner(group: "unseen")
        fixtures(checks)
        rule(checks)
        await requests(checks)
        await demo(checks)
        return checks.result()
    }

    private static func fixtures(_ checks: CheckRunner) {
        if let sessions = try? FixtureSource.json("app/hello.json")?["sessions"]?.decode([Session].self),
           sessions.count == 2 {
            checks.expect(sessions[0].unseen, "a hello session carries the gateway's mark")
            checks.expect(!sessions[1].unseen, "and one without the field carries none")
        } else {
            checks.expect(false, "app/hello.json carries two sessions")
        }
        if let expected = FixtureSource.json("app/session.seen.json")?.objectValue {
            let built = GatewayRequest.seen(sessionID: expected["session_id"]?.stringValue ?? "").json
            checks.equal(built["type"], expected["type"], "session.seen is named as the fixture names it")
            checks.equal(built["session_id"], expected["session_id"], "and names the session the same way")
            checks.equal(Set(built.objectValue?.keys.map { $0 } ?? []), Set(expected.keys),
                         "and carries the fixture's fields and no others")
        } else {
            checks.expect(false, "app/session.seen.json exists")
        }
        if let route = try? FixtureSource.json("http/push.payload.badge.json")?["rc"]?.decode(PushRoute.self) {
            checks.equal(route.kind, .badge, "the badge-only push says what it is")
            checks.equal(route.badge, 0, "and carries the count")
        } else {
            checks.expect(false, "http/push.payload.badge.json decodes as a push route")
        }
        let alert = try? FixtureSource.json("http/push.payload.json")?["rc"]?.decode(PushRoute.self)
        checks.equal(alert?.badge, 1, "every push carries the count")
    }

    private static func rule(_ checks: CheckRunner) {
        func session(_ state: SessionState, _ control: SessionControl = .remote, unseen: Bool = false,
                     archived: Bool = false) -> Session {
            Session(sessionID: "s", deviceID: "d", agent: "claude", title: "t", cwd: "/",
                    state: state, control: control, archived: archived, unseen: unseen)
        }
        let running = session(.running)
        for waiting in [session(.needsApproval), session(.needsInput), session(.idle),
                        session(.idle, .shared), session(.readonly, .terminal)] {
            checks.expect(UnseenMark.next(previous: running, current: waiting),
                          "running → \(waiting.state) (\(waiting.control)) marks the session")
        }
        checks.expect(!UnseenMark.next(previous: session(.starting), current: session(.idle)),
                      "a session that only started has done nothing to look at")
        for quiet in [session(.idle, .none), session(.error), session(.stopped)] {
            checks.expect(!UnseenMark.next(previous: running, current: quiet),
                          "running → \(quiet.state) (\(quiet.control)) waits for nobody")
        }
        checks.expect(UnseenMark.next(previous: session(.needsApproval, unseen: true), current: session(.idle)),
                      "waiting → waiting keeps the mark")
        checks.expect(!UnseenMark.next(previous: session(.needsApproval), current: session(.idle)),
                      "and keeps its absence")
        checks.expect(!UnseenMark.next(previous: session(.idle, unseen: true), current: session(.running)),
                      "working again clears it")
        checks.expect(!UnseenMark.next(previous: session(.idle, unseen: true),
                                       current: session(.stopped, .none, archived: true)),
                      "and so does archiving")
        let marks = [session(.idle, unseen: true), session(.idle, unseen: true, archived: true), session(.idle)]
        checks.equal(UnseenMark.count(in: marks), 1, "the badge counts the unarchived sessions with the mark")
        checks.equal(UnseenMark.count(in: marks, excluding: "d/s"), 0,
                     "less the conversation in front of the person")
    }

    /// One `session.seen` per mark, and none for a session without one.
    @MainActor
    private static func requests(_ checks: CheckRunner) async {
        let channel = StoreChecks.ScriptedChannel()
        let connection = ConnectionStore()
        await connection.enterDemo(api: DemoGateway(resumeDelay: nil), channel: channel)
        await settle { connection.hasSnapshot }
        checks.equal(connection.unseenCount, 1, "the demo's hello carries one mark")
        let mac = DemoFixtures.macDeviceID

        await connection.markSeen(deviceID: mac, sessionID: DemoFixtures.liveSessionID)
        checks.equal(channel.requests(ofType: "session.seen").count, 0,
                     "a session without the mark asks for nothing")
        await connection.markSeen(deviceID: mac, sessionID: DemoFixtures.approvalSessionID)
        await connection.markSeen(deviceID: mac, sessionID: DemoFixtures.approvalSessionID)
        checks.equal(channel.requests(ofType: "session.seen").map { $0.body["session_id"]?.stringValue },
                     [DemoFixtures.approvalSessionID], "one request for one mark")

        guard var cleared = connection.session(deviceID: mac, sessionID: DemoFixtures.approvalSessionID) else {
            return checks.expect(false, "the marked session is listed")
        }
        cleared.unseen = false
        channel.emit(.sessionUpdated(cleared))
        await settle { connection.unseenCount == 0 }
        var marked = cleared
        marked.unseen = true
        channel.emit(.sessionUpdated(marked))
        await settle { connection.unseenCount == 1 }
        await connection.markSeen(deviceID: mac, sessionID: DemoFixtures.approvalSessionID)
        checks.equal(channel.requests(ofType: "session.seen").count, 2, "a new mark is a new request")
    }

    /// The demo keeps the mark by the gateway's rule, so the demo shows it.
    @MainActor
    private static func demo(_ checks: CheckRunner) async {
        let gateway = DemoGateway(echoDelay: .zero, resumeDelay: nil)
        let connection = ConnectionStore()
        await connection.enterDemo(api: gateway, channel: gateway)
        await settle { connection.hasSnapshot }
        func pi() -> Session? { connection.sessions.first { $0.sessionID == DemoFixtures.piSessionID } }

        _ = try? await gateway.request(.send(sessionID: DemoFixtures.piSessionID, text: "go"))
        await settle { pi()?.unseen == true }
        checks.expect(pi()?.unseen == true, "a demo turn that ends unwatched marks its session")
        checks.equal(connection.unseenCount, 2, "and the badge counts it with the one the demo started with")

        await connection.markSeen(deviceID: DemoFixtures.macDeviceID, sessionID: DemoFixtures.piSessionID)
        await settle { pi()?.unseen == false }
        checks.expect(pi()?.unseen == false, "and session.seen takes it off")

        _ = try? await gateway.request(.send(sessionID: DemoFixtures.piSessionID, text: "again"))
        await settle { pi()?.state == .running }
        checks.expect(pi()?.unseen == false, "a session at work carries no mark")
    }

    @MainActor
    private static func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try? await Task.sleep(for: .milliseconds(20))
        }
    }
}
