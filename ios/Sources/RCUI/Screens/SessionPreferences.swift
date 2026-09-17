import SwiftUI
import RCCore

/// Amendment A35 — the Sessions group of Settings.
///
/// One switch, and it is the account's rather than this phone's: the gateway
/// keeps it, so the browser, the phone and every device read the same value and
/// turning it off anywhere cancels every pending resume everywhere. A gateway
/// that predates the switch sends no preferences at all, and it is shown
/// disabled with a note saying so.
struct SessionPreferenceRows: View {
    @Environment(AppModel.self) private var model

    private var preferences: PreferencesStore { model.preferences }

    var body: some View {
        let store = preferences
        // Read here rather than inside the binding's getter: a value a closure
        // reads later is read outside the body SwiftUI is tracking, and the
        // control would then keep drawing the value it was first given.
        let offered = store.isOffered
        let value = store.resumeAfterLimit
        // The write is a request, so the setter starts one rather than
        // assigning; the store applies it before the round trip, and puts it
        // back with the gateway's reason if it is refused.
        Toggle("Resume after the limit resets",
               isOn: Binding(get: { value },
                             set: { next in Task { await store.setResumeAfterLimit(next) } }))
            .font(Theme.Text.label)
            .settingsRowLayout()
            .disabled(!offered)
            .accessibilityIdentifier("settings.resumeAfterLimit")
        if let error = preferences.errorMessage {
            SettingsFooter(error)
                .settingsRowLayout()
                .accessibilityIdentifier("settings.resumeAfterLimitFailed")
        }
    }
}
