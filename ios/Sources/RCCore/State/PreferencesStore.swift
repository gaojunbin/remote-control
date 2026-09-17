import Foundation
import Observation

/// Amendment A35: the account's preferences, as this app reads and writes them.
///
/// They are the gateway's, not the phone's, so nothing here is stored locally:
/// the value is seeded from `hello`, replaced by `preferences.updated` whenever
/// another app or another device changes it, and written with
/// `PATCH /api/preferences`. `nil` means the gateway sent none, which is a
/// gateway older than the amendment, and the switch is shown disabled.
@MainActor
@Observable
public final class PreferencesStore {
    public private(set) var preferences: Preferences?
    /// What the last write failed with, once, for the screen to show.
    public private(set) var errorMessage: String?
    /// True while a write is out, so the switch cannot start a second one.
    public private(set) var isWriting = false

    @ObservationIgnored private var api: (any GatewayAPI)?

    public init() {}

    /// Whether this gateway offers the switches at all.
    public var isOffered: Bool { preferences != nil }

    /// The one preference there is today. False whenever it is not offered.
    public var resumeAfterLimit: Bool { preferences?.resumeAfterLimit ?? false }

    /// Bind the store to the connection's HTTP client. Called on every sign-in,
    /// and with nil on sign-out, which also forgets the previous account's value.
    public func attach(api: (any GatewayAPI)?) {
        self.api = api
        if api == nil { preferences = nil }
        errorMessage = nil
    }

    /// The socket's own copy. `hello` seeds it; `preferences.updated` replaces
    /// it, which is how turning the switch off in the browser reaches the phone.
    public func receive(_ frame: AppFrame) {
        switch frame {
        case .hello(let hello):
            preferences = hello.preferences
        case .preferencesUpdated(let value):
            preferences = value
        default:
            break
        }
    }

    /// Write the switch. The value is applied before the round trip so the
    /// control answers the finger, and put back with the reason if the gateway
    /// refuses; a `preferences.updated` on the socket confirms it either way.
    public func setResumeAfterLimit(_ value: Bool) async {
        guard let api, let previous = preferences, !isWriting else { return }
        errorMessage = nil
        preferences = Preferences(resumeAfterLimit: value)
        isWriting = true
        defer { isWriting = false }
        do {
            let answer = try await api.patchPreferences(resumeAfterLimit: value)
            preferences = answer.preferences
        } catch {
            preferences = previous
            errorMessage = GatewayMessage.text(for: error)
        }
    }

    public func clearError() { errorMessage = nil }
}
