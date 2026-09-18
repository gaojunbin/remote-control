import SwiftUI
import RCCore

/// Who you are, what happens while you are away, and how the app reads.
///
/// `docs/DESIGN.md` § "The Settings screen" (owner's ruling, 2026-09-18): a
/// header on the canvas, four groups named for the question they answer —
/// Account, While you're away, Voice, Reading — then Security, which is the
/// phone's own, and the versions to close it. Every row is a title and one
/// sentence with its control at the trailing edge; nothing is a footnote, and
/// no rule is drawn between two rows.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var showsDiagnostics = false
    @State private var confirmSignOut = false
    @State private var showsPassword = false
    @State private var showsUsers = false

    var body: some View {
        @Bindable var settings = model.settings
        // Read once and handed down: a sentence a store built with
        // `L10n.string` keeps the language it was built in, so the groups are
        // rebuilt when the language changes rather than when they are left and
        // re-entered.
        let language = settings.language
        Form {
            Section {
                SettingsIdentityHeader(user: model.connection.user, host: host,
                                       phase: model.connection.phase)
                    .canvasRow()
            }
            SettingsAccountGroup(language: language,
                                 users: { showsUsers = true },
                                 changePassword: { showsPassword = true },
                                 signOut: { confirmSignOut = true })
            SettingsAwayGroup(language: language)
            SettingsVoiceGroup(language: language)
            SettingsReadingGroup(language: language)
            SettingsSecurityGroup(language: language)
            Section {
                SettingsVersionsRow(gatewayVersion: model.connection.gatewayVersion,
                                    language: language,
                                    diagnostics: { showsDiagnostics = true })
                    .canvasRow()
            }
        }
        .scrollContentBackground(.hidden)
        .pageBackground()
        .navigationTitle("Settings")
        .onAppear { model.attachPush() }
        .onChange(of: settings.notificationsEnabled) { _, enabled in
            model.push.setEnabled(enabled)
            if enabled { model.push.requestAuthorizationIfNeeded() }
        }
        // The Users row is a button like the rows beside it, so the screen it
        // opens is pushed from here rather than by a link inside the group.
        .navigationDestination(isPresented: $showsUsers) {
            UsersView().environment(model)
        }
        .sheet(isPresented: $showsDiagnostics) {
            DiagnosticsView(report: report)
        }
        .sheet(isPresented: $showsPassword) {
            PasswordSheet().environment(model)
        }
        .confirmationDialog("Sign out of this gateway?", isPresented: $confirmSignOut,
                            titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { Task { await model.signOut() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            // What the row promised is what the dialog explains.
            Text(SettingsAccountGroup.signOutSentence)
        }
    }

    /// The gateway origin without its scheme. The demo reaches nothing, so it
    /// says what it is instead of naming a host nobody can visit.
    private var host: String {
        guard !model.isDemo, let origin = model.connection.endpoint?.origin else {
            return L10n.string("Demo")
        }
        return GatewayHost.of(origin)
    }

    private var report: String {
        let system = ProcessInfo.processInfo.operatingSystemVersion
        #if os(iOS)
        let platform = "iOS"
        #else
        let platform = "macOS preview host"
        #endif
        return model.settings.diagnosticReport(
            appVersion: AppBuild.version, platform: platform,
            osVersion: "\(system.majorVersion).\(system.minorVersion).\(system.patchVersion)",
            phase: model.connection.phase,
            deviceCount: model.connection.devices.count,
            sessionCount: model.connection.sessions.count,
            sttEnabled: model.connection.stt.enabled,
            isDemo: model.isDemo)
    }
}

extension View {
    /// A row that is not in a group: the header and the versions line sit on
    /// the canvas, at the margin the group surfaces are drawn to.
    fileprivate func canvasRow() -> some View {
        listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
    }
}

#Preview("Settings") {
    DemoPreview { NavigationStack { SettingsView() } }
}
