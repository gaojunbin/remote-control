import Testing
import Foundation
@testable import RCCore

/// Amendment A44: the gateway detects the dictation language, so a language
/// is offered only where the phone's own recogniser listens, and there it is
/// the recogniser's list, Chinese first, with no Automatic.
@Suite("Amendment A44, the dictation language follows the recogniser")
struct DictationLanguageTests {
    @Test("The phone listens wherever the gateway does not transcribe")
    func backendInEffect() {
        let table: [(VoiceBackend, Bool, VoiceBackend)] = [
            (.onDevice, true, .onDevice),
            (.onDevice, false, .onDevice),
            (.gateway, true, .gateway),
            // A gateway with no transcription service falls back to the phone,
            // and the phone then needs a language as much as if it was chosen.
            (.gateway, false, .onDevice)
        ]
        for (chosen, transcribes, expected) in table {
            #expect(VoiceBackend.inEffect(chosen: chosen, gatewayTranscribes: transcribes) == expected,
                    "\(chosen) on a gateway that transcribes: \(transcribes)")
        }
    }

    @Test("The recogniser's languages, Chinese first, and no Automatic")
    func theList() {
        #expect(DictationLanguage.codes == ["zh", "en", "ja", "de", "fr", "es"])
        #expect(!DictationLanguage.codes.contains("auto"))
        #expect(DictationLanguage.standard == "zh")
    }

    @Test("Unset, auto and anything unknown are heard as Chinese")
    func effectiveLanguage() {
        #expect(DictationLanguage.effective(nil) == "zh")
        #expect(DictationLanguage.effective("auto") == "zh")
        #expect(DictationLanguage.effective("ko") == "zh")
        #expect(DictationLanguage.effective("en") == "en")
        #expect(DictationLanguage.effective("es") == "es")
    }

    /// Every locale here is one `SFSpeechRecognizer.supportedLocales()` lists on
    /// the iOS 27 simulator, checked there in round 53; this test holds the
    /// mapping to what was checked.
    @Test("Each language is handed to the recogniser as a locale it supports")
    func locales() {
        let mapped = DictationLanguage.codes.map(DictationLanguage.localeIdentifier(for:))
        #expect(mapped == ["zh-CN", "en-US", "ja-JP", "de-DE", "fr-FR", "es-ES"])
        #expect(DictationLanguage.localeIdentifier(for: "auto") == "zh-CN")
    }

    @Test("A language is named in the app's own language")
    func names() {
        #expect(DictationLanguage.name(of: "zh", in: .en) == "Chinese")
        #expect(DictationLanguage.name(of: "en", in: .en) == "English")
        #expect(DictationLanguage.name(of: "zh", in: .zhHans) == "中文")
    }

    @Test("A new install listens for Chinese, and a stored auto is read as it without a write")
    @MainActor
    func settingsDefault() {
        let defaults = UserDefaults(suiteName: "rc-a44-\(UUID().uuidString)")!
        let settings = SettingsStore(defaults: defaults)
        #expect(settings.voiceLanguage == "zh")
        #expect(settings.speechLocaleIdentifier == "zh-CN")

        defaults.set("auto", forKey: "preference.voiceLanguage")
        let legacy = SettingsStore(defaults: defaults)
        #expect(legacy.voiceLanguage == "auto", "the stored value is left as it was")
        #expect(legacy.dictationLanguage == "zh")
        #expect(legacy.speechLocaleIdentifier == "zh-CN")
        #expect(defaults.string(forKey: "preference.voiceLanguage") == "auto", "and nothing rewrote it")
    }
}
