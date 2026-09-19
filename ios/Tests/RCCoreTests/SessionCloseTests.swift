import Testing
import Foundation
@testable import RCCore

/// Amendment A39: Close asks first only where something is lost.
@Suite("Closing a session asks only while the agent is working")
struct SessionCloseTests {
    private func session(_ state: SessionState, control: SessionControl = .remote) -> Session {
        Session(sessionID: "s", deviceID: "d", agent: "claude", title: "Work", cwd: "/src",
                state: state, control: control)
    }

    @Test("A turn under way is the one row that is asked about")
    func working() {
        for state in [SessionState.running, .starting] {
            #expect(SessionClose.asksFirst(session(state), online: true))
        }
    }

    @Test("Everything at rest, or waiting on the person, closes on the tap")
    func quiet() {
        for state in [SessionState.idle, .readonly, .needsApproval, .needsInput, .stopped, .error] {
            #expect(!SessionClose.asksFirst(session(state), online: true))
        }
    }

    @Test("A machine nobody can reach has no turn to lose")
    func offline() {
        #expect(!SessionClose.asksFirst(session(.running), online: false))
    }

    @Test("The question follows the dot, so the two can never disagree")
    func followsTheDot() {
        for state in [SessionState.running, .starting, .idle, .readonly, .needsApproval,
                      .needsInput, .stopped, .error] {
            for online in [true, false] {
                let row = session(state)
                #expect(SessionClose.asksFirst(row, online: online)
                    == (row.dotTone(online: online) == .working))
            }
        }
    }
}
