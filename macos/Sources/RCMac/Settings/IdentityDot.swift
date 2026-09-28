import RCCore

/// `connectionTone.ts`: the dot in the Settings header, from the app socket
/// alone (`docs/DESIGN.md` § "The Settings screen") — green while the socket
/// is open, which is the web's `open` and includes the moment before `hello`
/// lands; pulsing amber while it is being made or remade; grey while it is
/// down. The ruling's red for a refused gateway never reaches a browser, which
/// the gateway signs out instead, but it does reach this app: a connection
/// another app took over, or one speaking a protocol this build does not, stays
/// on screen until the person acts.
enum IdentityDot {
    static func tone(_ phase: ConnectionPhase) -> DotTone {
        switch phase {
        case .syncing, .connected: .working
        case .connecting, .reconnecting: .waiting
        case .signedOut: .off
        case .expired, .forbidden, .superseded, .incompatible: .failed
        }
    }

    /// The word for that tone. It is read aloud and shown on hover, never printed.
    static func word(_ phase: ConnectionPhase) -> String {
        switch tone(phase) {
        case .working: S.settings.connected
        case .waiting: S.settings.connecting
        case .failed: S.macSettings.refused
        default: S.settings.offline
        }
    }
}
