import Testing
import Foundation
@testable import RCCore

/// The dot on a session row, in the chat header and anywhere else it appears.
/// The rule reads all three facts, because `state` alone cannot tell a finished
/// turn on a live session from one whose CLI exited: both report `idle`.
@Suite("Status dot: state, owner and reachability")
struct StatusDotTests {
    private let owners: [SessionControl] = [.remote, .terminal, .shared, .none]

    @Test("A turn under way is green, whoever started it")
    func working() {
        for control in owners {
            #expect(DotTone.of(state: .starting, control: control, online: true) == .working)
            #expect(DotTone.of(state: .running, control: control, online: true) == .working)
        }
    }

    @Test("A session blocked on the user is amber, and the only tone that moves")
    func waiting() {
        for control in owners {
            #expect(DotTone.of(state: .needsApproval, control: control, online: true) == .waiting)
            #expect(DotTone.of(state: .needsInput, control: control, online: true) == .waiting)
        }
    }

    @Test("Quiet and still owned is amber; quiet and owned by nothing is grey")
    func liveAndExited() {
        for state in [SessionState.idle, .readonly] {
            #expect(DotTone.of(state: state, control: .remote, online: true) == .live)
            #expect(DotTone.of(state: state, control: .terminal, online: true) == .live)
            #expect(DotTone.of(state: state, control: .shared, online: true) == .live)
            #expect(DotTone.of(state: state, control: .none, online: true) == .off)
        }
    }

    @Test("An error is red, and a stopped session is grey")
    func failedAndStopped() {
        for control in owners {
            #expect(DotTone.of(state: .error, control: control, online: true) == .failed)
            #expect(DotTone.of(state: .stopped, control: control, online: true) == .off)
        }
    }

    @Test("A machine that cannot be reached reports nothing, whatever it last said")
    func offline() {
        for state in [SessionState.starting, .running, .needsApproval, .needsInput,
                      .idle, .readonly, .stopped, .error] {
            for control in owners {
                #expect(DotTone.of(state: state, control: control, online: false) == .off)
            }
        }
    }

    @Test("A state this build has never heard of claims nothing")
    func unknownState() {
        #expect(DotTone.of(state: SessionState(rawValue: "compacting"),
                           control: .remote, online: true) == .off)
    }

    @Test("A session reads its own tone from the device it runs on")
    func fromASession() {
        let session = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "Work",
                              cwd: "/src", state: .idle, control: .shared)
        #expect(session.dotTone(online: true) == .live)
        #expect(session.dotTone(online: false) == .off)
    }
}

/// The key to the colours the Sessions screen draws above its list.
@Suite("Dot legend: four colours, four words")
struct DotLegendTests {
    @Test("Four entries, in the order they are read")
    func order() {
        #expect(DotLegend.entries.map(\.text) == ["Working", "For you", "Not running", "Error"])
    }

    @Test("One entry per colour, and the amber one is the still one")
    func tones() {
        #expect(DotLegend.entries.map(\.tone) == [.working, .live, .off, .failed])
        #expect(!DotLegend.entries.contains { $0.tone == .waiting })
    }

    @Test("Every tone the dot can take is spoken for")
    func everyToneIsCovered() {
        let spoken = Set(DotLegend.entries.map(\.tone)).union([DotTone.waiting])
        #expect(spoken == Set(DotTone.allCases))
    }
}
