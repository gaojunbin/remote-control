import SwiftUI
import LocalAuthentication
import RCCore

/// Face ID, Touch ID or the device passcode, before the transcript is shown.
struct AppLockView: View {
    let onUnlock: () -> Void
    @State private var authenticating = false
    @State private var error: String?
    var body: some View {
        VStack(spacing: 22) {
            AppMark(size: 62)
            Text(L10n.string("Remote Control is locked")).font(.title2.weight(.medium))
            Text(L10n.string("Unlock with Face ID, Touch ID or your passcode to see your sessions."))
                .font(.subheadline).foregroundStyle(Theme.inkSecondary).multilineTextAlignment(.center)
            if let error { Text(error).font(.caption).foregroundStyle(.secondary) }
            Button {
                authenticating = true
                Task {
                    do {
                        let context = LAContext()
                        if try await context.evaluatePolicy(
                            .deviceOwnerAuthentication,
                            localizedReason: L10n.string("Unlock Remote Control")) { onUnlock() }
                    } catch { self.error = L10n.string("Not unlocked. You can try again.") }
                    authenticating = false
                }
            } label: {
                HStack {
                    if authenticating { ProgressView() }
                    Label(L10n.string("Unlock"), systemImage: "lock.open")
                }
                .frame(minWidth: 160, minHeight: 48)
            }.buttonStyle(PrimaryButtonStyle(fullWidth: false)).disabled(authenticating)
        }.padding(32).frame(maxWidth: .infinity, maxHeight: .infinity).background(Theme.canvas).accessibilityElement(children: .contain)
            .accessibilityIdentifier("app.lock")
    }
}

/// What the app switcher sees instead of a transcript.
struct AppPrivacyCover: View {
    var body: some View {
        VStack(spacing: 16) {
            AppMark(size: 64)
            Text("Remote Control").font(.title2.weight(.medium)).foregroundStyle(Theme.ink)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.canvas)
    }
}
