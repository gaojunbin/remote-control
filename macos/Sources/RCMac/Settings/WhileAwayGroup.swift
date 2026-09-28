import AppKit
import RCCore
import SwiftUI

/// `WhileAwayGroup.tsx`: what happens while nobody is looking — the
/// notification that says a session needs you, and A35's resume after a usage
/// limit resets. Both are switches, and both say in their own row why they
/// cannot be flipped (`docs/DESIGN.md` § "The Settings screen").
struct WhileAwayGroup: View {
    var body: some View {
        SettingsGroup(S.settings.whileAway) {
            NotifyRow()
            ResumeRow()
        }
    }
}

/// Notify me, which on the Mac lets the running app post the notifications
/// the gateway pushes for (`docs/DESIGN.md` § "The Mac app"). The switch is
/// this Mac's, not the account's, and the one way this app is refused is the
/// system's own setting — so that, in the Mac's words, is the only sentence
/// that replaces the row's.
struct NotifyRow: View {
    @Environment(MacAppModel.self) private var model
    @Environment(\.previewStage) private var stage

    var body: some View {
        let notifier = SettingsFeature.state(of: model).notifier
        // A render shows the refusal the system would give.
        let blocked = stage == "settings.notify-blocked" || notifier.permission == .denied
        let isOn = !blocked && notifier.isOn
        let inert = blocked || notifier.isAsking
        SettingsRow(title: S.settings.notify, sentence: blocked ? S.mac.pushBlocked : S.settings.notifyNote,
                    target: true, reach: inert ? nil : { turn(notifier, on: !isOn) }) {
            Switch(isOn: isOn, label: S.settings.notify) { turn(notifier, on: $0) }
                .disabled(inert)
        }
        .task { await notifier.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            Task { await notifier.refresh() }
        }
    }

    private func turn(_ notifier: TurnNotifier, on: Bool) {
        Task { await notifier.turn(on: on) }
    }
}

/// A35: the account's own switch, which the gateway holds. No preferences at
/// all is a gateway that predates them, not a switch that is off.
struct ResumeRow: View {
    @Environment(MacAppModel.self) private var model
    @Environment(\.previewStage) private var stage
    @State private var error: String?

    var body: some View {
        let store = model.preferences
        let offered = store.isOffered && stage != "settings.old-gateway"
        let sentence = offered ? (error ?? S.settings.resumeAfterLimitNote) : S.settings.resumeUnavailable
        let set = { (next: Bool) in
            error = nil
            Task {
                await store.setResumeAfterLimit(next)
                if store.errorMessage != nil {
                    error = S.errors.setFailed
                    store.clearError()
                }
            }
        }
        SettingsRow(title: S.settings.resumeAfterLimit, sentence: sentence, target: true,
                    reach: offered ? { set(!store.resumeAfterLimit) } : nil) {
            Switch(isOn: offered && store.resumeAfterLimit, label: S.settings.resumeAfterLimit) { set($0) }
                .disabled(!offered)
        }
    }
}
