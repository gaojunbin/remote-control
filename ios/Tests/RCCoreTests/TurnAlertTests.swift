import Testing
import Foundation
@testable import RCCore

/// When the app tells someone their turn ended, and when it says nothing.
///
/// The table is the gateway's: `transition_kind()` in
/// `gateway/rc_gateway/push.py`. A banner the app raises for itself and a push
/// the gateway sends have to mean the same thing, or one channel would announce
/// a turn the other stayed quiet about.
@Suite("Turn alerts: the transition that is worth a banner")
struct TurnAlertTests {
    @Test("A turn that finishes is announced, from every state a turn can be in")
    func finished() {
        for previous in [SessionState.running, .needsApproval, .needsInput] {
            #expect(TurnAlerts.kind(previous: previous, current: .idle) == .turnCompleted)
        }
    }

    @Test("A session blocked on the user is announced as what it is waiting for")
    func blocked() {
        #expect(TurnAlerts.kind(previous: .running, current: .needsApproval) == .needsApproval)
        #expect(TurnAlerts.kind(previous: .running, current: .needsInput) == .needsInput)
        #expect(TurnAlerts.kind(previous: .idle, current: .needsApproval) == .needsApproval)
        #expect(TurnAlerts.kind(previous: .needsInput, current: .needsApproval) == .needsApproval)
    }

    @Test("An error is announced from wherever it arrived")
    func errored() {
        for previous in [SessionState.starting, .running, .idle, .needsApproval, .stopped] {
            #expect(TurnAlerts.kind(previous: previous, current: .error) == .error)
        }
    }

    @Test("Nothing is announced for a session that was not working, or still is")
    func silent() {
        #expect(TurnAlerts.kind(previous: .starting, current: .running) == nil)
        #expect(TurnAlerts.kind(previous: .idle, current: .running) == nil)
        #expect(TurnAlerts.kind(previous: .idle, current: .idle) == nil)
        #expect(TurnAlerts.kind(previous: .idle, current: .stopped) == nil)
        #expect(TurnAlerts.kind(previous: .running, current: .stopped) == nil)
        #expect(TurnAlerts.kind(previous: .error, current: .error) == nil)
        // A CLI that exited reports `idle` too, and it never ran a turn here.
        #expect(TurnAlerts.kind(previous: .stopped, current: .idle) == nil)
        #expect(TurnAlerts.kind(previous: .readonly, current: .idle) == nil)
    }

    @Test("A session seen for the first time announces nothing")
    func firstSight() {
        #expect(TurnAlerts.kind(previous: nil, current: session(.idle)) == nil)
        #expect(TurnAlerts.kind(previous: nil, current: session(.error)) == nil)
        #expect(TurnAlerts.kind(previous: session(.running), current: session(.idle)) == .turnCompleted)
    }

    @Test("Sessions are keyed by device and id together, so two of them are never one transition")
    func keyedByDeviceAndSession() {
        #expect(TurnAlerts.kind(previous: session(.running, id: "other"),
                                current: session(.idle)) == nil)
        #expect(TurnAlerts.kind(previous: session(.running, device: "other"),
                                current: session(.idle)) == nil)
    }

    private func session(_ state: SessionState, id: String = "s1", device: String = "d1") -> Session {
        Session(sessionID: id, deviceID: device, agent: "claude", title: "Fix the flake",
                cwd: "/w", state: state)
    }
}

/// The idle timer is held off in a conversation and nowhere else.
@Suite("The screen stays awake in a conversation")
struct ScreenAwakeTests {
    @Test("A conversation in the foreground holds the screen")
    func inConversation() {
        #expect(ScreenAwakeRule.awake(chatOnScreen: true, sceneActive: true))
    }

    @Test("Leaving the conversation, or the foreground, gives the timer straight back")
    func everywhereElse() {
        #expect(!ScreenAwakeRule.awake(chatOnScreen: true, sceneActive: false))
        #expect(!ScreenAwakeRule.awake(chatOnScreen: false, sceneActive: true))
        #expect(!ScreenAwakeRule.awake(chatOnScreen: false, sceneActive: false))
    }
}
