import Foundation
import RCCore

/// What the composer may do for one session, read the way `Composer.tsx` reads
/// it: from `control`, never from `state` (A7), and from what the agent says an
/// attachment carries (A10, A11, A40).
struct ComposerGates: Equatable {
    let terminalControlled: Bool
    /// A10: a live CLI owns the session and the device is attached to it.
    let shared: Bool
    let running: Bool
    let canSteer: Bool
    let canTakeover: Bool
    /// The composer is gated on `control`, never on `state`: a mirrored session
    /// reports `running` while the terminal drives the turn.
    let disabled: Bool
    /// A10: the channel cannot interrupt a running turn, so neither can we.
    let canInterrupt: Bool
    /// A11: a control the attachment cannot drive is hidden, never disabled.
    let showAttach: Bool
    private let settable: Set<SharedSetting>

    init(session: Session, agent: AgentInfo?, deviceOnline: Bool) {
        terminalControlled = session.control == .terminal
        shared = session.control == .shared
        running = [.running, .needsApproval, .needsInput, .starting].contains(session.state)
        canSteer = agent?.supports(.steer) ?? false
        canTakeover = agent?.supports(.takeover) ?? false
        disabled = terminalControlled || !deviceOnline
        canInterrupt = shared ? Attach.canInterruptShared(agent) : true
        showAttach = !shared || Attach.canAttachShared(agent)
        // A17/A40: a terminal session refuses `session.set` outright, and a
        // shared one takes only the settings its agent carries; what the
        // device cannot change is shown as the value the terminal chose.
        let terminal = terminalControlled
        let isShared = shared
        settable = Set(SharedSetting.allCases.filter { key in
            !terminal && (!isShared || Attach.canSetShared(agent, key))
        })
    }

    /// Whether this one setting is the device's to change from here.
    func canSet(_ key: SharedSetting) -> Bool { settable.contains(key) }
}
