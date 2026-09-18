import SwiftUI
import RCCore

/// "While you're away": the two switches that decide what happens when nobody
/// is looking at the phone — being told, and the device carrying on (A35).
///
/// `docs/DESIGN.md` § "The Settings screen": each switch says in its own row
/// what it does, and a row that cannot do anything says why there instead.
struct SettingsAwayGroup: View {
    @Environment(AppModel.self) private var model
    /// Held so a change of interface language rebuilds the sentences where
    /// they stand (`SettingsLabel`).
    let language: InterfaceLanguage

    private var push: PushController { model.push }

    var body: some View {
        @Bindable var settings = model.settings
        SettingsGroup("While you're away") {
            if push.authorization == .denied {
                // The one settings row on the phone whose tap is not the
                // control's: iOS is the only place this can be turned back on,
                // so the row goes there and the switch stays inert.
                Button { push.openSystemSettings() } label: {
                    HStack(alignment: .center, spacing: Theme.Space.medium) {
                        SettingsLabel("Notify me", sentence: blockedSentence)
                        Toggle(isOn: .constant(false)) { EmptyView() }
                            .labelsHidden()
                            .disabled(true)
                            .allowsHitTesting(false)
                    }
                    .settingsRowPadding()
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("settings.notifications.blocked")
            } else {
                Toggle(isOn: $settings.notificationsEnabled) {
                    SettingsLabel("Notify me", sentence: notifySentence)
                }
                // The app's own alerts need no gateway, so the switch works in
                // the demo too wherever the system can raise one.
                .disabled(!push.isSupported)
                .settingsRowPadding()
                .accessibilityIdentifier("settings.notifications")
            }
            ResumeAfterLimitRow(language: language)
        }
    }

    private var blockedSentence: String {
        L10n.string("Blocked in iOS Settings. Tap to open them.")
    }

    /// What the switch does, or what the system or the gateway did to it.
    private var notifySentence: String {
        guard push.isSupported else { return push.statusText }
        guard push.status != .refused else { return push.statusText }
        return L10n.string("Which device and session needs you, and nothing else.")
    }
}

/// Amendment A35 — the account's switch, not this phone's.
///
/// The gateway keeps it, so the browser, the phone and every device read the
/// same value and turning it off anywhere cancels every pending resume
/// everywhere. A gateway that predates the switch offers nothing and the row
/// says so; a refused write puts the switch back and says why.
private struct ResumeAfterLimitRow: View {
    @Environment(AppModel.self) private var model
    let language: InterfaceLanguage

    private var preferences: PreferencesStore { model.preferences }

    var body: some View {
        let store = preferences
        // Read here rather than inside the binding's getter: a value a closure
        // reads later is read outside the body SwiftUI is tracking, and the
        // control would then keep drawing the value it was first given.
        let offered = store.isOffered
        let value = store.resumeAfterLimit
        let sentence = store.errorMessage ?? ResumeText.settingsSentence(offered: offered)
        // The write is a request, so the setter starts one rather than
        // assigning; the store applies it before the round trip, and puts it
        // back with the gateway's reason if it is refused.
        Toggle(isOn: Binding(get: { value },
                             set: { next in Task { await store.setResumeAfterLimit(next) } })) {
            SettingsLabel("Resume after the limit resets", sentence: sentence)
        }
        .disabled(!offered)
        .settingsRowPadding()
        .accessibilityIdentifier("settings.resumeAfterLimit")
    }
}
