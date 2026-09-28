import RCCore
import SwiftUI

/// `VoiceGroup.tsx` — A29: dictation, who transcribes it, and whether a model
/// tidies it up before it is sent. A gateway that offers no transcription and
/// a gateway with no polish model each say so in their own row; the group
/// itself never collapses to a note.
struct VoiceGroup: View {
    @Environment(MacAppModel.self) private var model
    @Environment(\.previewStage) private var stage

    var body: some View {
        let settings = model.settings
        // A render of a gateway with no transcription service, or no polish model.
        let transcribes = model.connection.stt.enabled && stage != "settings.no-transcription"
        let offered = model.connection.polish.enabled && stage != "settings.polish-unavailable"
        let staged = stage == "settings.polish-on" || stage == "settings.polish-menu"
        let polishOn = offered && (settings.polishEnabled || staged)
        SettingsGroup(S.settings.voice) {
            // A44: the Mac, like the web, only transcribes on the gateway,
            // whose provider detects the language, so the row names the
            // gateway and has nothing to choose — no menu, and no language.
            SettingsRow(title: S.settings.transcribe,
                        sentence: transcribes ? S.settings.transcribeNote : S.settings.voiceServerDisabled) {
                QuietChip(text: S.settings.transcribeGateway)
            }
            // The switch is always drawn, so the feature exists even where this
            // gateway cannot offer it; the model and the strength appear once
            // it is on, because they mean nothing while it is off.
            SettingsRow(title: S.settings.polish,
                        sentence: offered ? S.settings.polishNote : S.settings.polishServerDisabled,
                        target: true, reach: offered ? { settings.polishEnabled = !polishOn } : nil) {
                Switch(isOn: polishOn, label: S.settings.polish) { settings.polishEnabled = $0 }
                    .disabled(!offered)
            }
            if polishOn { PolishRows() }
        }
    }
}

/// `.pill.quiet` on a span: a chip that reads as state rather than as a
/// control — no tint, the secondary ink, almost no padding, and nothing to
/// click.
struct QuietChip: View {
    let text: String

    var body: some View {
        Text(text)
            .lineLimit(1)
            .css(FontSize.fs13)
            .foregroundStyle(Palette.inkSecondary)
            .padding(.horizontal, 2)
            .frame(height: 28)
    }
}
