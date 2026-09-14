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
///
/// `docs/DESIGN.md` § "Accounts": the app's own settings belong to the person
/// signed in, not to the app. Every preference below is stored under a key that
/// carries the gateway origin and the username, so two people who share one
/// phone find their own language, dictation language and notification choices.
/// The gateway address is the exception and stays global: it is how the form is
/// prefilled and there is nobody to scope it to until someone has signed in.
/// The username is kept per gateway, so alternating between two of them
/// prefills each with the account that was used there.
@MainActor
@Observable
public final class SettingsStore {
    private enum Key {
        static let gateway = "gateway."
        static let origin = "gateway.origin"
        static let username = "gateway.username"
        static let prefix = "preference."
        static let notifications = "preference.notifications"
        static let appLock = "preference.appLock"
        static let voiceBackend = "preference.voiceBackend"
        static let voiceLanguage = "preference.voiceLanguage"
        static let polishEnabled = "preference.polishEnabled"
        static let polishModel = "preference.polishModel"
        static let polishStrength = "preference.polishStrength"
        static let timelineDetail = "preference.timelineDetail"
        static let language = "preference.language"
    }

    @ObservationIgnored private let defaults: UserDefaults
    /// Whose preferences are being read and written: `<origin>|<username>`, or
    /// nothing at all before anyone has signed in on this install.
    @ObservationIgnored public private(set) var scope = ""
    /// A launch argument fixes the language for the whole run, so a test reads
    /// the app in the language it asked for whichever account signs in.
    @ObservationIgnored private var pinnedLanguage: InterfaceLanguage?

    public var lastOrigin: String { didSet { defaults.set(lastOrigin, forKey: Key.origin) } }
    public var notificationsEnabled: Bool { didSet { write(notificationsEnabled, Key.notifications) } }
    public var appLockEnabled: Bool { didSet { write(appLockEnabled, Key.appLock) } }
    public var voiceBackend: VoiceBackend { didSet { write(voiceBackend.rawValue, Key.voiceBackend) } }
    /// A BCP-47 code, or "auto" to let the gateway decide.
    public var voiceLanguage: String { didSet { write(voiceLanguage, Key.voiceLanguage) } }
    /// Amendment A29: whether a finished dictation is passed through the
    /// gateway's polish model. Off by default — nothing leaves the phone for a
    /// model until the person asks for it.
    public var polishEnabled: Bool { didSet { write(polishEnabled, Key.polishEnabled) } }
    /// Which of the provider's models does the polishing. Empty until one is
    /// chosen, which is when the feature can take effect.
    public var polishModel: String { didSet { write(polishModel, Key.polishModel) } }
    public var polishStrength: PolishStrength {
        didSet { write(polishStrength.rawValue, Key.polishStrength) }
    }
    /// How much of a transcript is drawn. Simple is the default: most of what an
    /// agent does is not addressed to the reader.
    public var timelineDetail: TimelineDetail {
        didSet { write(timelineDetail.rawValue, Key.timelineDetail) }
    }
    /// Which language the app writes its own words in. Changing it moves the
    /// table every string outside a `Text` is looked up in, so the whole app
    /// follows the next time it draws, which is at once.
    public var language: InterfaceLanguage {
        didSet {
            write(language.rawValue, Key.language)
            L10n.use(language)
        }
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let origin = defaults.string(forKey: Key.origin) ?? ""
        let username = defaults.string(forKey: "\(Key.username)@\(origin)") ?? ""
        lastOrigin = origin
        notificationsEnabled = false
        appLockEnabled = false
        voiceBackend = .onDevice
        voiceLanguage = "auto"
        polishEnabled = false
        polishModel = ""
        polishStrength = .moderate
        timelineDetail = .simple
        language = .en
        // The account the app is about to come back to owns the preferences it
        // reads on launch, so the first screen is already in their language.
        scope = Self.scope(origin: origin, username: username)
        readScopedValues()
        L10n.use(language)
    }

    // MARK: - Who the form is prefilled with

    /// The account that last signed in on one gateway, which is what the form
    /// offers when that gateway is typed. Empty where nobody has.
    public func username(for origin: String) -> String {
        origin.isEmpty ? "" : defaults.string(forKey: "\(Key.username)@\(origin)") ?? ""
    }

    /// The account on the gateway the app comes back to, which is the one the
    /// keychain token is filed under.
    public var lastUsername: String { username(for: lastOrigin) }

    // MARK: - Scoping

    private static func scope(origin: String, username: String) -> String {
        origin.isEmpty && username.isEmpty ? "" : "\(origin)|\(username)"
    }

    private func key(_ name: String) -> String { scope.isEmpty ? name : "\(name)@\(scope)" }

    private func write(_ value: Any, _ name: String) { defaults.set(value, forKey: key(name)) }

    /// Read every preference from the current scope, falling back to the value
    /// a fresh install has. A launch-pinned language wins over what was stored.
    private func readScopedValues() {
        notificationsEnabled = defaults.bool(forKey: key(Key.notifications))
        appLockEnabled = defaults.bool(forKey: key(Key.appLock))
        voiceBackend = VoiceBackend(rawValue: defaults.string(forKey: key(Key.voiceBackend)) ?? "") ?? .onDevice
        voiceLanguage = defaults.string(forKey: key(Key.voiceLanguage)) ?? "auto"
        polishEnabled = defaults.bool(forKey: key(Key.polishEnabled))
        polishModel = defaults.string(forKey: key(Key.polishModel)) ?? ""
        polishStrength = PolishStrength(rawValue: defaults.string(forKey: key(Key.polishStrength)) ?? "")
            ?? .moderate
        timelineDetail = TimelineDetail(rawValue: defaults.string(forKey: key(Key.timelineDetail)) ?? "")
            ?? .simple
        // English whatever the phone is set to: the default is the product's
        // own language and not a guess from `Locale.preferredLanguages`.
        language = pinnedLanguage
            ?? InterfaceLanguage(rawValue: defaults.string(forKey: key(Key.language)) ?? "")
            ?? .en
    }

    /// Point the preferences at one account. Called on every sign-in, including
    /// the restore on launch, so signing in as someone else changes what the
    /// app remembers rather than inheriting the last person's choices.
    public func adopt(origin: String, username: String) {
        let next = Self.scope(origin: origin, username: username)
        guard next != scope else { return }
        scope = next
        readScopedValues()
    }

    /// Fix the interface language for this run, whatever any account stored.
    public func pinLanguage(_ value: InterfaceLanguage) {
        pinnedLanguage = value
        language = value
    }

    /// Start as a fresh install: every account's preferences, not only the
    /// current one's, so a run never inherits the shape an earlier run left.
    public func reset() {
        for name in defaults.dictionaryRepresentation().keys
        where name.hasPrefix(Key.prefix) || name.hasPrefix(Key.gateway) {
            defaults.removeObject(forKey: name)
        }
        lastOrigin = ""
        scope = ""
        readScopedValues()
    }

    /// The locale handed to `SFSpeechRecognizer`, resolved from the preference.
    public var speechLocaleIdentifier: String {
        voiceLanguage == "auto" ? Locale.current.identifier : voiceLanguage
    }

    /// Remember who signed in where, and read their preferences.
    public func remember(origin: String, username: String) {
        lastOrigin = origin
        if !origin.isEmpty { defaults.set(username, forKey: "\(Key.username)@\(origin)") }
        adopt(origin: origin, username: username)
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
        Dictation polish: \(polishEnabled ? "on (\(polishStrength.rawValue))" : "off")
        Timeline detail: \(timelineDetail.rawValue)
        Interface language: \(language.rawValue)
        Notifications: \(notificationsEnabled ? "on" : "off")
        App lock: \(appLockEnabled ? "on" : "off")

        Excludes the gateway address, account, device and session identifiers,
        credentials, message text, file paths, attachments and raw error details.
        """
    }
}
