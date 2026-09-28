import Foundation

/// Amendment A44: the language this iPhone's own recogniser listens for.
///
/// `SFSpeechRecognizer` cannot detect a language; it listens for the one it is
/// given. So where the phone transcribes, the person picks one, and nowhere
/// else: the gateway's provider detects the language itself and is told
/// nothing. The list is the recogniser's rather than the gateway's
/// `stt.languages` (always `["auto"]` since A44), and has no Automatic.
///
/// The account's `stt_language` holds the code. Unset, `auto` (written before
/// A44) or a code the list does not hold reads as Chinese, and is left where it
/// stands rather than written back.
public enum DictationLanguage {
    /// Each language with the locale `SFSpeechRecognizer` is handed for it,
    /// Chinese first. Every locale here is one `supportedLocales()` lists and
    /// `SFSpeechRecognizer(locale:)` accepts on the iOS 27 simulator (round 53).
    private static let locales: KeyValuePairs<String, String> = [
        "zh": "zh-CN",
        "en": "en-US",
        "ja": "ja-JP",
        "de": "de-DE",
        "fr": "fr-FR",
        "es": "es-ES"
    ]

    /// What the recogniser is offered, in the order the menus list them.
    public static let codes = locales.map(\.key)

    /// What a new install listens for, and what anything unknown reads as.
    public static let standard = "zh"

    /// The code the phone listens for, given what the account or this phone
    /// stored.
    public static func effective(_ stored: String?) -> String {
        guard let stored, codes.contains(stored) else { return standard }
        return stored
    }

    /// The locale handed to `SFSpeechRecognizer` for a code.
    public static func localeIdentifier(for code: String) -> String {
        let listened = effective(code)
        return locales.first { $0.key == listened }?.value ?? "zh-CN"
    }

    /// A language's name in the app's own language, not the phone's: the
    /// menu is one of the app's words.
    public static func name(of code: String, in language: InterfaceLanguage) -> String {
        language.locale.localizedString(forLanguageCode: code) ?? code
    }

    /// Amendment A44: the polish request's language hint — `auto` for words
    /// the gateway transcribed, the code the phone listened for otherwise.
    public static func polishHint(backend: VoiceBackend, listening code: String) -> String {
        backend == .gateway ? "auto" : effective(code)
    }
}
