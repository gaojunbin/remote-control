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
        case .onDevice: L10n.string("On this iPhone")
        case .gateway: L10n.string("Gateway")
        }
    }

    public var explanation: String {
        switch self {
        case .onDevice:
            L10n.string("Audio stays on this device. Needs an on-device model for the language you pick.")
        case .gateway:
            L10n.string("Audio is streamed to your gateway for transcription.")
        }
    }
}

/// How much of a transcript is drawn. The level is a reading preference: it is
/// kept on this device, never sent to the gateway or the machine, and the store
/// holds every block whichever level is chosen.
public enum TimelineDetail: String, Sendable, Codable, CaseIterable {
    /// Only what is written to the reader.
    case simple
    /// Everything the agent did, including thinking and every tool call.
    case detailed

    public var title: String {
        switch self {
        case .simple: L10n.string("Simple")
        case .detailed: L10n.string("Detailed")
        }
    }

    public var explanation: String {
        switch self {
        case .simple: L10n.string("Simple shows only what is written to you.")
        case .detailed: L10n.string("Detailed adds thinking, tool calls and the task list.")
        }
    }

    /// The sentence under the control. A choice between two options has to
    /// describe both, so it is the explanations in the order they are offered.
    public static var footnote: String {
        allCases.map(\.explanation).joined(separator: " ")
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
        static let timelineDetail = "preference.timelineDetail"
        static let language = "preference.language"
    }

    @ObservationIgnored private let defaults: UserDefaults

    public var lastOrigin: String { didSet { defaults.set(lastOrigin, forKey: Key.origin) } }
    public var lastUsername: String { didSet { defaults.set(lastUsername, forKey: Key.username) } }
    public var notificationsEnabled: Bool { didSet { defaults.set(notificationsEnabled, forKey: Key.notifications) } }
    public var appLockEnabled: Bool { didSet { defaults.set(appLockEnabled, forKey: Key.appLock) } }
    public var voiceBackend: VoiceBackend { didSet { defaults.set(voiceBackend.rawValue, forKey: Key.voiceBackend) } }
    /// A BCP-47 code, or "auto" to let the gateway decide.
    public var voiceLanguage: String { didSet { defaults.set(voiceLanguage, forKey: Key.voiceLanguage) } }
    /// How much of a transcript is drawn. Simple is the default: most of what an
    /// agent does is not addressed to the reader.
    public var timelineDetail: TimelineDetail {
        didSet { defaults.set(timelineDetail.rawValue, forKey: Key.timelineDetail) }
    }
    /// Which language the app writes its own words in. Changing it moves the
    /// table every string outside a `Text` is looked up in, so the whole app
    /// follows the next time it draws, which is at once.
    public var language: InterfaceLanguage {
        didSet {
            defaults.set(language.rawValue, forKey: Key.language)
            L10n.use(language)
        }
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        lastOrigin = defaults.string(forKey: Key.origin) ?? ""
        lastUsername = defaults.string(forKey: Key.username) ?? ""
        notificationsEnabled = defaults.bool(forKey: Key.notifications)
        appLockEnabled = defaults.bool(forKey: Key.appLock)
        voiceBackend = VoiceBackend(rawValue: defaults.string(forKey: Key.voiceBackend) ?? "") ?? .onDevice
        voiceLanguage = defaults.string(forKey: Key.voiceLanguage) ?? "auto"
        timelineDetail = TimelineDetail(rawValue: defaults.string(forKey: Key.timelineDetail) ?? "") ?? .simple
        // English whatever the phone is set to: the default is the product's
        // own language and not a guess from `Locale.preferredLanguages`.
        language = InterfaceLanguage(rawValue: defaults.string(forKey: Key.language) ?? "") ?? .en
        L10n.use(language)
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
        Timeline detail: \(timelineDetail.rawValue)
        Interface language: \(language.rawValue)
        Notifications: \(notificationsEnabled ? "on" : "off")
        App lock: \(appLockEnabled ? "on" : "off")

        Excludes the gateway address, account, device and session identifiers,
        credentials, message text, file paths, attachments and raw error details.
        """
    }
}
