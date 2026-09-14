import Foundation
import RCCore

/// The two rules behind "tell me when the turn ends" and "don't lock the screen
/// on me": both are pure, so both are checked here rather than in the simulator.
enum AlertChecks {
    @MainActor
    static func run() async -> CheckResult {
        let checks = CheckRunner(group: "alerts")
        transitions(checks)
        firstSight(checks)
        awake(checks)
        await storeHook(checks)
        return checks.result()
    }

    /// The table is `transition_kind()` in `gateway/rc_gateway/push.py`, and the
    /// app must read it the same way or one channel would announce a turn the
    /// other stays quiet about.
    private static func transitions(_ checks: CheckRunner) {
        let table: [(SessionState, SessionState, PushKind?)] = [
            (.running, .idle, .turnCompleted),
            (.needsApproval, .idle, .turnCompleted),
            (.needsInput, .idle, .turnCompleted),
            (.running, .needsApproval, .needsApproval),
            (.running, .needsInput, .needsInput),
            (.idle, .needsApproval, .needsApproval),
            (.running, .error, .error),
            (.idle, .error, .error),
            (.starting, .running, nil),
            (.idle, .idle, nil),
            (.idle, .stopped, nil),
            (.idle, .running, nil),
            (.stopped, .idle, nil),
            (.readonly, .idle, nil),
            (.error, .error, nil),
            (.running, .readonly, nil)
        ]
        for (previous, current, expected) in table {
            checks.equal(TurnAlerts.kind(previous: previous, current: current), expected,
                         "\(previous.rawValue) → \(current.rawValue)")
        }
    }

    /// A session the app is seeing for the first time did not just transition:
    /// the `hello` list is what it knows, not something that happened.
    private static func firstSight(_ checks: CheckRunner) {
        func session(_ state: SessionState, id: String = "s1", device: String = "d1") -> Session {
            Session(sessionID: id, deviceID: device, agent: "claude", title: "t", cwd: "/w", state: state)
        }
        checks.expect(TurnAlerts.kind(previous: nil, current: session(.idle)) == nil,
                      "a session with nothing before it announces nothing")
        checks.equal(TurnAlerts.kind(previous: session(.running), current: session(.idle)),
                     .turnCompleted, "and one the app already held announces its finished turn")
        checks.expect(TurnAlerts.kind(previous: session(.running, id: "other"),
                                      current: session(.idle)) == nil,
                      "two different sessions are not a transition")
        checks.expect(TurnAlerts.kind(previous: session(.running, device: "other"),
                                      current: session(.idle)) == nil,
                      "and neither are two devices' sessions that share an id")
    }

    /// Where the app learns of a transition: the store hands over both versions
    /// of a session it already held, and nothing at all for the `hello` list.
    @MainActor
    private static func storeHook(_ checks: CheckRunner) async {
        let gateway = DemoGateway(echoDelay: .milliseconds(50))
        let store = ConnectionStore()
        var pairs: [(Session, Session)] = []
        store.onSessionTransition = { previous, current in pairs.append((previous, current)) }
        await store.enterDemo(api: gateway, channel: gateway)
        await settle { store.hasSnapshot }
        checks.equal(pairs.count, 0, "a hello is a list, not a transition")

        guard let idle = store.sessions.first(where: {
            $0.state == .idle && $0.control == .remote && !$0.archived
        }) else {
            checks.expect(false, "the demo carries an idle session to type into")
            return
        }
        let chat = ChatStore(session: idle, channel: gateway)
        store.addFrameHandler("alerts") { [weak chat] frame in chat?.receive(frame) }
        await chat.open()
        chat.draft = "run the suite again"
        await chat.send()
        await settle(timeout: 10) {
            pairs.contains { TurnAlerts.kind(previous: $0.0, current: $0.1) == .turnCompleted }
        }
        let kinds = pairs.filter { $0.1.id == idle.id }.compactMap { TurnAlerts.kind(previous: $0.0, current: $0.1) }
        checks.equal(kinds, [.turnCompleted], "a scripted turn announces its end, once")
        checks.expect(pairs.allSatisfy { $0.0.id == $0.1.id },
                      "and each pair is one session before and after")
        await store.signOut()
    }

    @MainActor
    private static func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline { try? await Task.sleep(for: .milliseconds(20)) }
    }

    private static func awake(_ checks: CheckRunner) {
        checks.expect(ScreenAwakeRule.awake(chatOnScreen: true, sceneActive: true),
                      "a conversation in the foreground holds the screen awake")
        checks.expect(!ScreenAwakeRule.awake(chatOnScreen: true, sceneActive: false),
                      "sending the app to the background gives the timer back")
        checks.expect(!ScreenAwakeRule.awake(chatOnScreen: false, sceneActive: true),
                      "the list and Settings leave the timer alone")
        checks.expect(!ScreenAwakeRule.awake(chatOnScreen: false, sceneActive: false),
                      "and so does a backgrounded app that is on neither")
    }
}
