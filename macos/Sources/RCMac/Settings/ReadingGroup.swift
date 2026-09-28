import RCCore
import SwiftUI

/// `ReadingGroup.tsx`: how the app reads — the words it uses for itself, and
/// how much of a turn the timeline draws. Two choices each, so both are
/// segmented controls in the row (`docs/DESIGN.md` § "The Settings screen").
/// Both are the account's (A41): a change goes up, and one made elsewhere
/// moves the control in place.
struct ReadingGroup: View {
    @Environment(MacAppModel.self) private var model

    var body: some View {
        let settings = model.settings
        SettingsGroup(S.settings.reading) {
            SettingsRow(title: S.settings.language, sentence: S.settings.languageNote) {
                Segmented(value: settings.language, options: [InterfaceLanguage.en, .zhHans].map {
                    SegmentOption(value: $0, label: S.interfaceLanguageLabel($0))
                }, ariaLabel: S.settings.language) { settings.language = $0 }
            }
            SettingsRow(title: S.settings.timelineDetail, sentence: S.settings.timelineDetailNote) {
                Segmented(value: settings.timelineDetail, options: [TimelineDetail.simple, .detailed].map {
                    SegmentOption(value: $0, label: S.timelineDetailLabel($0))
                }, ariaLabel: S.settings.timelineDetail) { settings.timelineDetail = $0 }
            }
        }
    }
}
