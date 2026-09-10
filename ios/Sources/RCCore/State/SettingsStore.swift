import Foundation
import Observation

/// Where speech becomes text.
public enum VoiceBackend: String, Sendable, Codable, CaseIterable {
    /// `SFSpeechRecognizer` with on-device recognition. Audio never leaves the phone.
    case onDevice
    /// The gateway's streaming endpoint. Audio is uploaded to your own gateway.
    case gateway

    public var title: String {
        switch self {
        case .onDevice: "On this iPhone"
        case .gateway: "Gateway"
        }
    }

    public var explanation: String {
        switch self {
        case .onDevice: "Audio stays on this device. Needs an on-device model for the language you pick."
        case .gateway: "Audio is streamed to your gateway for transcription."
        }
    }
}

/// Preferences that outlive one connection. Everything here is a plain value in
/// `UserDefaults`; the bearer token lives in the keychain instead.
@MainActor
@Observable
public final class SettingsStore {
    private enum Key {
        static let origin = "gateway.origin"
        static let username = "gateway.username"
        static let notifications = "preference.notifications"
        static let appLock = "preference.appLock"
        static let voiceBackend = "preference.voiceBackend"
        static let voiceLanguage = "preference.voiceLanguage"
    }

    @ObservationIgnored private let defaults: UserDefaults

    public var lastOrigin: String { didSet { defaults.set(lastOrigin, forKey: Key.origin) } }
    public var lastUsername: String { didSet { defaults.set(lastUsername, forKey: Key.username) } }
    public var notificationsEnabled: Bool { didSet { defaults.set(notificationsEnabled, forKey: Key.notifications) } }
    public var appLockEnabled: Bool { didSet { defaults.set(appLockEnabled, forKey: Key.appLock) } }
    public var voiceBackend: VoiceBackend { didSet { defaults.set(voiceBackend.rawValue, forKey: Key.voiceBackend) } }
    /// A BCP-47 code, or "auto" to let the gateway decide.
    public var voiceLanguage: String { didSet { defaults.set(voiceLanguage, forKey: Key.voiceLanguage) } }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        lastOrigin = defaults.string(forKey: Key.origin) ?? ""
        lastUsername = defaults.string(forKey: Key.username) ?? ""
        notificationsEnabled = defaults.bool(forKey: Key.notifications)
        appLockEnabled = defaults.bool(forKey: Key.appLock)
        voiceBackend = VoiceBackend(rawValue: defaults.string(forKey: Key.voiceBackend) ?? "") ?? .onDevice
        voiceLanguage = defaults.string(forKey: Key.voiceLanguage) ?? "auto"
    }

    /// The locale handed to `SFSpeechRecognizer`, resolved from the preference.
    public var speechLocaleIdentifier: String {
        voiceLanguage == "auto" ? Locale.current.identifier : voiceLanguage
    }

    public func remember(origin: String, username: String) {
        lastOrigin = origin
        lastUsername = username
    }

    /// A diagnostic report built from an explicit allowlist.
    ///
    /// Never serialize a store and redact afterwards: this function names every
    /// field it emits, so nothing new can leak by being added elsewhere.
    public func diagnosticReport(appVersion: String, platform: String, osVersion: String,
                                 phase: ConnectionPhase, deviceCount: Int, sessionCount: Int,
                                 sttEnabled: Bool, isDemo: Bool) -> String {
        let connection: String = switch phase {
        case .signedOut: "signed out"
        case .connecting: "connecting"
        case .syncing: "syncing"
        case .connected: "connected"
        case .reconnecting: "reconnecting"
        case .expired: "session expired"
        case .forbidden: "refused by the gateway"
        case .superseded: "replaced by another connection"
        case .incompatible: "protocol mismatch"
        }
        return """
        Remote Control for iOS — diagnostic snapshot
        App: \(appVersion)
        Platform: \(platform)
        OS: \(osVersion)
        Protocol: v\(RemoteProtocol.version)
        Cache schema: v\(LocalCache.schemaVersion)
        Mode: \(isDemo ? "offline demo" : "gateway")
        Connection: \(connection)
        Devices known: \(deviceCount)
        Sessions known: \(sessionCount)
        Gateway transcription: \(sttEnabled ? "available" : "unavailable")
        Voice backend: \(voiceBackend.rawValue)
        Notifications: \(notificationsEnabled ? "on" : "off")
        App lock: \(appLockEnabled ? "on" : "off")

        Excludes the gateway address, account, device and session identifiers,
        credentials, message text, file paths, attachments and raw error details.
        """
    }
}
