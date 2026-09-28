import Foundation
import RCCore

/// What the launch arguments ask of this run.
///
/// - `--demo`: the offline demo gateway around the app, as the iPhone app's.
/// - `--demo-account`: the offline demo behind the sign-in form instead, so the
///   account screens can be driven with no gateway (`--registration-open` opens
///   its registrations).
/// - `--demo-update-required`: the demo claims a minimum above this build,
///   which is how the blocking Update required screen is reached (A45).
/// - `--ephemeral`: nothing of the person's is read or written — the token in
///   memory, preferences in a throwaway suite, caches and drafts in a scratch
///   directory, all removed on quit. Every automated run uses it.
/// - `--reset-state`: start as a fresh install.
/// - `--language=en|zh-Hans`: the interface language for the whole run.
public struct LaunchOptions: Sendable, Hashable {
    public var demo: Bool
    public var demoAccount: Bool
    public var registrationOpen: Bool
    public var demoUpdateRequired: Bool
    public var ephemeral: Bool
    public var resetState: Bool
    public var language: InterfaceLanguage?

    public init(demo: Bool = false, demoAccount: Bool = false, registrationOpen: Bool = false,
                demoUpdateRequired: Bool = false, ephemeral: Bool = false, resetState: Bool = false,
                language: InterfaceLanguage? = nil) {
        self.demo = demo
        self.demoAccount = demoAccount
        self.registrationOpen = registrationOpen
        self.demoUpdateRequired = demoUpdateRequired
        self.ephemeral = ephemeral
        self.resetState = resetState
        self.language = language
    }

    public init(arguments: [String]) {
        self.init(demo: arguments.contains("--demo"),
                  demoAccount: arguments.contains("--demo-account"),
                  registrationOpen: arguments.contains("--registration-open"),
                  demoUpdateRequired: arguments.contains("--demo-update-required"),
                  ephemeral: arguments.contains("--ephemeral"),
                  resetState: arguments.contains("--reset-state"),
                  language: arguments.first { $0.hasPrefix("--language=") }
                      .flatMap { InterfaceLanguage(rawValue: String($0.dropFirst("--language=".count))) })
    }

    /// This process's own arguments.
    public static var current: LaunchOptions { LaunchOptions(arguments: ProcessInfo.processInfo.arguments) }
}
