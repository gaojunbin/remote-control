import Foundation
import RCCore

/// `web/src/features/chat/StatusLine.tsx`: the single line under the timeline
/// that explains what happens next, or nothing at all when there is nothing to
/// explain (`docs/DESIGN.md` § "Status vocabulary").
public struct StatusLineModel: Equatable, Sendable {
    public enum Tone: Sendable, Equatable { case running, attention, error, muted }

    public let tone: Tone
    public let text: String
    /// Whether the line ends in a Take over link: only where the agent can be
    /// taken over, which is also where the words invite it.
    public let offersTakeover: Bool

    public init(tone: Tone, text: String, offersTakeover: Bool = false) {
        self.tone = tone
        self.text = text
        self.offersTakeover = offersTakeover
    }

    /// The line for a session, or nil where the web draws none.
    ///
    /// `editingQueued` is A43: the composer holds a queued message being edited,
    /// which goes back into the line even behind a steering agent — so the line
    /// says queued, as the button beside the field does.
    public static func of(session: Session, agent: AgentInfo?, deviceOnline: Bool,
                          editingQueued: Bool = false) -> StatusLineModel? {
        let name = S.agentLabel(session.agent)
        let steers = (agent?.supports(.steer) ?? false) && !editingQueued
        let canTakeover = agent?.supports(.takeover) ?? false

        if !deviceOnline { return StatusLineModel(tone: .muted, text: S.status.offline) }

        if session.control == .terminal {
            let busy = session.state == .running || session.state == .starting
            // This line says who holds the session, what is happening there,
            // and how to write to it — that last clause only where the agent
            // can be taken over, which is also where the button is.
            let clauses = [S.status.terminalControlled,
                           busy ? S.status.terminalBusy : nil,
                           canTakeover ? S.status.takeOverToSend : nil].compactMap { $0 }
            return StatusLineModel(tone: busy ? .running : .muted, text: clauses.joined(separator: " · "),
                                   offersTakeover: canTakeover)
        }

        switch session.state {
        case .running:
            return StatusLineModel(tone: .running,
                                   text: steers ? S.status.workingSteer(name) : S.status.workingQueued(name))
        case .starting:
            return StatusLineModel(tone: .running, text: S.status.starting)
        case .needsApproval:
            return StatusLineModel(tone: .attention, text: S.status.needsApproval)
        case .needsInput:
            return StatusLineModel(tone: .attention, text: S.status.needsInput)
        case .error:
            return StatusLineModel(tone: .error, text: session.stateDetail ?? S.status.errored)
        case .stopped:
            return StatusLineModel(tone: .muted, text: S.status.stopped)
        default:
            return nil
        }
    }
}
