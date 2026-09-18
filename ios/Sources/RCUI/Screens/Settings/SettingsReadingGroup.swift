import SwiftUI
import RCCore

/// The Reading group: the words the app writes, and how much of a transcript
/// it draws.
///
/// `docs/DESIGN.md` § "The Settings screen": two options are a segmented
/// control in the row, so both of these are one — the title and its sentence on
/// the left, the control at the trailing edge at its own width.
struct SettingsReadingGroup: View {
    @Environment(AppModel.self) private var model
    /// Held so a change of interface language rebuilds the sentences where
    /// they stand (`SettingsLabel`).
    let language: InterfaceLanguage

    var body: some View {
        @Bindable var settings = model.settings
        SettingsGroup("Reading") {
            SettingsRow("Language",
                        sentence: L10n.string("The app's own words only; what the agent wrote stays as written.")) {
                Picker("Language", selection: $settings.language) {
                    // Each name in its own script, so the one you want is
                    // recognisable from inside the language you cannot read.
                    ForEach(InterfaceLanguage.allCases, id: \.self) { language in
                        Text(verbatim: language.title).tag(language)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
                .accessibilityIdentifier("settings.language")
            }
            SettingsRow("Detail", sentence: TimelineDetail.footnote) {
                Picker("Detail", selection: $settings.timelineDetail) {
                    ForEach(TimelineDetail.allCases, id: \.self) { level in
                        Text(level.title).tag(level)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
                .accessibilityIdentifier("settings.timelineDetail")
            }
        }
    }
}
