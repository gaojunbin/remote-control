import SwiftUI
import RCCore

/// The Voice group: where the words come from, in which language, and what
/// happens to them afterwards (A29).
///
/// `docs/DESIGN.md` § "The Settings screen": the backend's own sentence is the
/// Transcribe row's, and a gateway with no transcription service or no polish
/// model says so in the row it belongs to rather than under the group.
///
/// The polish rows are written here rather than in a view of their own, so the
/// group is one body and one file.
struct SettingsVoiceGroup: View {
    @Environment(AppModel.self) private var model
    /// Held so a change of interface language rebuilds the sentences where
    /// they stand (`SettingsLabel`).
    let language: InterfaceLanguage

    /// The provider's models, as this gateway lists them (A29).
    @State private var models: [PolishModel] = []
    @State private var listFailed = false

    /// The gateway has a polish model, so the switch can take effect. It is the
    /// one thing the gateway has a say in here; everything else is the person's
    /// own preference, off by default.
    private var canPolish: Bool { model.connection.polish.enabled }

    var body: some View {
        @Bindable var settings = model.settings
        SettingsGroup("Voice") {
            SettingsMenuRow("Transcribe", sentence: transcribeSentence,
                            selection: $settings.voiceBackend,
                            chosen: settings.voiceBackend.title) {
                ForEach(VoiceBackend.allCases, id: \.self) { backend in
                    Text(backend.title).tag(backend)
                }
            }
            .disabled(!model.connection.stt.enabled && settings.voiceBackend == .onDevice)
            .accessibilityIdentifier("settings.voiceBackend")
            // Named for what it is: the interface language is the Reading
            // group's setting, and two rows called "Language" on one screen is
            // a riddle rather than a preference.
            SettingsMenuRow("Dictation language",
                            sentence: L10n.string("The language you dictate in; Automatic lets the recogniser decide."),
                            selection: $settings.voiceLanguage,
                            chosen: dictationLanguageName) {
                Text("Automatic").tag("auto")
                ForEach(languageCodes, id: \.self) { code in
                    Text(languageName(code)).tag(code)
                }
            }
            .accessibilityIdentifier("settings.voiceLanguage")
            // Amendment A29: dictation is the setting above; what happens to
            // the words afterwards is the setting below it.
            SettingsRow("Polish dictation with AI", sentence: polishSentence) {
                Toggle("Polish dictation with AI", isOn: $settings.polishEnabled)
                    .labelsHidden()
                    .disabled(!canPolish)
                    .accessibilityIdentifier("settings.polish")
                    // The models are asked for when the switch appears, and
                    // again if the gateway's answer about polishing changes
                    // under it.
                    .task(id: canPolish) { await loadModels() }
            }

            if canPolish, settings.polishEnabled {
                SettingsMenuRow("Model", sentence: modelSentence,
                                selection: $settings.polishModel, chosen: modelName) {
                    // A model that is not on the list — none chosen yet, or one
                    // the provider has since dropped — still needs a row, or the
                    // picker would show someone else's choice.
                    if models.allSatisfy({ $0.id != settings.polishModel }) {
                        Text(modelName).tag(settings.polishModel)
                    }
                    ForEach(models) { option in Text(option.label).tag(option.id) }
                }
                .accessibilityIdentifier("settings.polishModel")
                SettingsMenuRow("Strength",
                                sentence: L10n.string("Moderate cleans up. Strong also restructures and resolves references."),
                                selection: $settings.polishStrength,
                                chosen: settings.polishStrength.title) {
                    ForEach(PolishStrength.allCases, id: \.self) { strength in
                        Text(strength.title).tag(strength)
                    }
                }
                .accessibilityIdentifier("settings.polishStrength")
            }
        }
    }

    /// The chosen backend's own sentence, or the reason the choice is not
    /// really one: a gateway with no transcription service leaves the phone's
    /// own recogniser as the only way to dictate.
    private var transcribeSentence: String {
        if model.settings.voiceBackend == .gateway && !model.connection.stt.enabled {
            return L10n.string(
                "This gateway has no transcription service configured, so dictation falls back to on-device recognition.")
        }
        return model.settings.voiceBackend.explanation
    }

    /// What polishing sends, and when. A gateway with no polish model says so
    /// instead, under a switch that cannot be turned on.
    private var polishSentence: String {
        guard canPolish else { return L10n.string("This gateway has no polish model configured") }
        return L10n.string(
            "Sends what you dictated and the last few messages to this gateway's model. Nothing is sent while it is off.")
    }

    /// The word at the trailing edge of the Dictation language row.
    private var dictationLanguageName: String {
        let code = model.settings.voiceLanguage
        return code == "auto" ? L10n.string("Automatic") : languageName(code)
    }

    /// The word at the trailing edge of the Model row: the label the gateway
    /// gave the chosen model, the bare id while the list has not arrived, or
    /// an invitation while nothing is chosen.
    private var modelName: String {
        let chosen = model.settings.polishModel
        if let option = models.first(where: { $0.id == chosen }) { return option.label }
        return chosen.isEmpty ? L10n.string("Choose a model") : chosen
    }

    private var modelSentence: String {
        listFailed ? L10n.string("The model list could not be loaded.")
                   : L10n.string("From the list this gateway serves.")
    }

    private var languageCodes: [String] {
        let offered = model.connection.stt.languages.filter { $0 != "auto" }
        return offered.isEmpty ? ["en", "zh", "ja", "de", "fr", "es"] : offered
    }

    private func languageName(_ code: String) -> String {
        Locale.current.localizedString(forLanguageCode: code) ?? code
    }

    /// The models the gateway offers. A failure says so in the Model row and
    /// leaves the picker alone: the model already chosen still works, and a
    /// gateway that answers next time fills the list.
    private func loadModels() async {
        guard canPolish, let api = model.connection.api else { return }
        do {
            let answer = try await api.polishModels()
            guard !Task.isCancelled else { return }
            models = answer.models
            listFailed = false
            // Nothing is polished without a model, so the first one the gateway
            // offers is taken rather than leaving the switch inert.
            if model.settings.polishModel.isEmpty, let first = answer.models.first {
                model.settings.polishModel = first.id
            }
        } catch {
            guard !Task.isCancelled else { return }
            listFailed = true
        }
    }
}
