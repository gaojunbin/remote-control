import Foundation

/// The language the app writes its own words in.
///
/// It is a reading preference like the timeline detail: it changes menus,
/// buttons, captions, status lines, placeholders and accessible names, and
/// nothing else. What the agent wrote, what the device reported — a name, a
/// path, a branch, a model or permission id — and anything the reader typed
/// are never translated. English is the default whatever the system language
/// is, because the product is written in English and a phone set to another
/// language is not a request to change it.
public enum InterfaceLanguage: String, Sendable, Codable, CaseIterable {
    case en
    case zhHans = "zh-Hans"

    /// Each name in its own script: a reader who cannot read the language the
    /// app is in has to be able to recognise the one they want.
    public var title: String {
        switch self {
        case .en: "English"
        case .zhHans: "中文"
        }
    }

    /// What `\.locale` is set to at the root, which is how SwiftUI resolves
    /// every `Text` in the app.
    public var locale: Locale { Locale(identifier: rawValue) }

    /// The table a string built outside a `Text` is looked up in. The main
    /// bundle is the fallback, so a process with no catalogue at all — a check
    /// runner, a unit test — reads every key as the English it is written in.
    var table: Bundle {
        guard let path = Bundle.main.path(forResource: rawValue, ofType: "lproj"),
              let bundle = Bundle(path: path) else { return .main }
        return bundle
    }
}

/// The app's own words, in the language the reader chose rather than the one
/// the system is set to.
///
/// SwiftUI resolves a `Text` through the environment's locale, but a string
/// built in a store, an error or a notification has no environment to read, so
/// it comes through here instead. One preference, one catalogue, both ways.
public enum L10n {
    /// The chosen language's table, readable from any thread: an error
    /// description is asked for wherever the error was caught.
    private final class Current: @unchecked Sendable {
        private let lock = NSLock()
        private var stored = Bundle.main

        func adopt(_ value: Bundle) {
            lock.lock()
            defer { lock.unlock() }
            stored = value
        }

        var bundle: Bundle {
            lock.lock()
            defer { lock.unlock() }
            return stored
        }
    }

    private static let current = Current()

    /// Point every later lookup at one language. `SettingsStore` calls this on
    /// launch and whenever the preference changes.
    public static func use(_ language: InterfaceLanguage) { current.adopt(language.table) }

    /// The translation, or the key itself where there is none — which is the
    /// English text, because every key is its own English text.
    public static func string(_ key: String) -> String {
        current.bundle.localizedString(forKey: key, value: key, table: nil)
    }

    /// A translation with values in it; the key carries the format specifiers.
    public static func string(_ key: String, _ arguments: any CVarArg...) -> String {
        String(format: string(key), arguments: arguments)
    }
}
