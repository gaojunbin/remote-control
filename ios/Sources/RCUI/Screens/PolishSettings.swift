import SwiftUI
import RCCore

/// Amendment A29 — the dictation-polish rows of the Voice group.
///
/// One switch, the model the gateway serves, and how hard it may work. The
/// gateway has a say in exactly one thing: whether a polish model is configured
/// at all. Everything else here is the person's own preference, off by default.
struct PolishSettingsRows: View {
    @Environment(AppModel.self) private var model
    @State private var models: [PolishModel] = []
    @State private var listFailed = false

    /// The gateway has a model, so the switch can take effect.
    private var isAvailable: Bool { model.connection.polish.enabled }

    var body: some View {
        @Bindable var settings = model.settings
        Toggle("Polish dictation with AI", isOn: $settings.polishEnabled)
            .font(Theme.Text.label)
            .settingsRowLayout()
            .disabled(!isAvailable)
            .accessibilityIdentifier("settings.polish")
            // Asked for when the group appears, and again if the gateway's
            // answer about polishing changes under it.
            .task(id: isAvailable) { await loadModels() }

        if isAvailable, settings.polishEnabled {
            Picker("Model", selection: $settings.polishModel) {
                // A model that is not on the list — none chosen yet, or one the
                // provider has since dropped — still needs a row, or the picker
                // would show someone else's choice.
                if models.allSatisfy({ $0.id != settings.polishModel }) {
                    Text(settings.polishModel.isEmpty
                         ? L10n.string("Choose a model") : settings.polishModel)
                        .tag(settings.polishModel)
                }
                ForEach(models) { option in Text(option.label).tag(option.id) }
            }
            .font(Theme.Text.label)
            .settingsRowLayout()
            .accessibilityIdentifier("settings.polishModel")

            if listFailed {
                SettingsFooter(L10n.string("The model list could not be loaded."))
                    .settingsRowLayout()
                    .accessibilityIdentifier("settings.polishModelsFailed")
            }

            Picker("Strength", selection: $settings.polishStrength) {
                ForEach(PolishStrength.allCases, id: \.self) { strength in
                    Text(strength.title).tag(strength)
                }
            }
            .font(Theme.Text.label)
            .settingsRowLayout()
            .accessibilityIdentifier("settings.polishStrength")
        }
    }

    /// The provider's models, as the gateway lists them. A failure says so in
    /// one line and leaves the picker alone: the model already chosen still
    /// works, and a gateway that answers next time fills the list.
    private func loadModels() async {
        guard isAvailable, let api = model.connection.api else { return }
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
