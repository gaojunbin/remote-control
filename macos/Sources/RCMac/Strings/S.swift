import Foundation
import Observation
import RCCore
import Synchronization

/// The app's own words: `S.<group>.<key>`, read through the interface
/// language every time, as the web's `strings` proxy reads its table.
///
/// Two things follow, as they do on the web (`docs/WEB.md` § "Language"). A
/// view reading `S` inside `body` redraws when the language changes, because
/// the read goes through an observed value. And **nothing may read a string at
/// static-init time**: a table built in a `static let` keeps the language the
/// app launched in. Build such lists inside a function or a computed property.
///
/// Only the app's own words are here. Everything a device reported — agent
/// output, device names, paths, branches, model and permission labels — is
/// drawn as it arrived.
public enum S {
    /// Never translated: both of the web's tables spell it the same way.
    public static let productName = "Remote Control"
}

/// The interface language `S` reads, observed so that every view which read a
/// string redraws when it changes. The model moves it — to the signed-in
/// account's choice (A41), and to English on the login page — and nothing else
/// writes it. Readable from any thread: a notification or an error
/// is worded wherever it is built.
public final class InterfaceLanguageSource: Observable, Sendable {
    public static let shared = InterfaceLanguageSource()

    private let registrar = ObservationRegistrar()
    private let storage = Mutex(InterfaceLanguage.en)

    init() {}

    public var current: InterfaceLanguage {
        get {
            registrar.access(self, keyPath: \.current)
            return storage.withLock { $0 }
        }
        set {
            guard storage.withLock({ $0 }) != newValue else { return }
            registrar.withMutation(of: self, keyPath: \.current) {
                storage.withLock { $0 = newValue }
            }
        }
    }
}

/// The two timeline detail levels, in the order Settings offers them.
public struct TimelineDetailLabels: Sendable {
    public let simple: String
    public let detailed: String

    public subscript(detail: TimelineDetail) -> String {
        switch detail {
        case .simple: simple
        case .detailed: detailed
        }
    }
}
