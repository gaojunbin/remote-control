import RCCore
import SwiftUI

/// `PolishRows` in `VoiceGroup.tsx` — A29: the model and the strength, drawn
/// while polish is on. The list belongs to the gateway's provider, so it is
/// asked for when these rows are drawn. A gateway that lists nothing leaves the
/// menu where it is, with what was chosen before; a list with nothing chosen
/// yet settles on its first model, so the switch is all a first-time reader has
/// to touch.
struct PolishRows: View {
    @Environment(MacAppModel.self) private var model
    @Environment(\.previewStage) private var stage
    @State private var models: [PolishModel] = []
    @State private var failed = false
    @State private var menuOpen = false

    var body: some View {
        let settings = model.settings
        VStack(spacing: 0) {
            SettingsRow(title: S.settings.polishModel,
                        sentence: failed ? S.settings.polishModelsFailed : S.settings.polishModelNote,
                        target: true, reach: { menuOpen.toggle() }) {
                Popover(isOpen: $menuOpen, align: .end, ariaLabel: S.settings.polishModel) {
                    Text(label(settings.polishModel))
                } content: { close in
                    MenuList {
                        ForEach(models) { option in
                            MenuItemRow(option.label, selected: settings.polishModel == option.id) {
                                settings.polishModel = option.id
                                close()
                            }
                        }
                    }
                    .accessibilityLabel(S.settings.polishModel)
                }
            }
            SettingsRow(title: S.settings.polishStrength, sentence: S.settings.polishStrengthNote) {
                Segmented(value: settings.polishStrength, options: [
                    SegmentOption(value: PolishStrength.moderate, label: S.settings.polishModerate),
                    SegmentOption(value: PolishStrength.strong, label: S.settings.polishStrong)
                ], ariaLabel: S.settings.polishStrength) { settings.polishStrength = $0 }
            }
        }
        .task { await loadModels() }
    }

    private func label(_ chosen: String) -> String {
        if let known = models.first(where: { $0.id == chosen }), !known.label.isEmpty { return known.label }
        return chosen.isEmpty ? S.settings.polishChooseModel : chosen
    }

    private func loadModels() async {
        guard let api = model.connection.api else { return }
        do {
            let result = try await api.polishModels()
            guard !Task.isCancelled else { return }
            models = result.models
            failed = false
            if let first = result.models.first, model.settings.polishModel.isEmpty {
                model.settings.polishModel = first.id
            }
            if stage == "settings.polish-menu" { menuOpen = true }
        } catch {
            if !Task.isCancelled { failed = true }
        }
    }
}
