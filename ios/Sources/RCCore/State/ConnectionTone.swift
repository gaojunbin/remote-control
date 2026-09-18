import Foundation

/// The dot beside the gateway in the Settings header, and the word for it.
///
/// `docs/DESIGN.md` § "The Settings screen": green while connected, pulsing
/// amber while the link is being made or remade, grey while it is down, red
/// when the gateway refused it. The word — Connected, Connecting, Offline,
/// Refused — is the accessibility label and nothing else: the dot carries the
/// state on screen, exactly as it does on a session row.
///
/// A pure function of the phase, so the header never decides this for itself.
public enum ConnectionTone {
    public static func dot(_ phase: ConnectionPhase) -> DotTone {
        switch phase {
        case .connected: .working
        case .connecting, .syncing, .reconnecting: .waiting
        case .signedOut: .off
        case .expired, .forbidden, .superseded, .incompatible: .failed
        }
    }

    public static func word(_ phase: ConnectionPhase) -> String {
        switch phase {
        case .connected: L10n.string("Connected")
        case .connecting, .syncing, .reconnecting: L10n.string("Connecting")
        case .signedOut: L10n.string("Offline")
        case .expired, .forbidden, .superseded, .incompatible: L10n.string("Refused")
        }
    }
}
