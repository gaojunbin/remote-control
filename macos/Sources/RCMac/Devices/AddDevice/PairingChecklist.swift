import Foundation
import RCCore

/// The rule `PairingProgress.tsx` lights the checklist by: three steps, the
/// first done from the start, each of the others active while the handshake is
/// on its way to it, and a hairline of progress over them.
enum PairingChecklist {
    enum Mark: Sendable, Equatable {
        case idle
        case active
        case done
    }

    /// No frame for this code yet ranks below the first step.
    static func rank(_ step: PairingStep?) -> Int { step?.order ?? -1 }

    /// The progress line's filled share, in per cent.
    static func progress(_ step: PairingStep?) -> Double {
        let rank = rank(step)
        return rank < 0 ? 12 : min(100, 25 * Double(rank + 1))
    }

    /// Gateway ready, Device handshake, Detect installed agents.
    static func marks(_ step: PairingStep?) -> [Mark] {
        let rank = rank(step)
        let enrolled = PairingStep.enrolled.order, online = PairingStep.online.order
        return [
            .done,
            rank >= enrolled ? .done : .active,
            rank >= PairingStep.agents.order ? .done : (rank == online ? .active : .idle)
        ]
    }

    /// The agents the new device found, by id, for the last step's line.
    static func agents(_ live: PairingProgress?) -> String {
        (live?.device?.agents.filter(\.available).map(\.agent) ?? []).joined(separator: " · ")
    }
}
