import SwiftUI
import RCCore

/// The Security group: the app lock, which is the phone's own and nobody
/// else's.
///
/// It is the last group because it is the one setting that guards everything
/// above it, and a terminal opened while it is on asks again (A38).
struct SettingsSecurityGroup: View {
    @Environment(AppModel.self) private var model
    /// Held so a change of interface language rebuilds the sentence where it
    /// stands (`SettingsLabel`).
    let language: InterfaceLanguage

    var body: some View {
        @Bindable var settings = model.settings
        SettingsGroup("Security") {
            Toggle(isOn: $settings.appLockEnabled) {
                SettingsLabel("Require Face ID",
                              sentence: L10n.string("Unlock with Face ID, Touch ID or your passcode when the app returns from the background."))
            }
            .settingsRowPadding()
            .accessibilityIdentifier("settings.appLock")
        }
    }
}
