import SwiftUI
import RCCore

/// Gateway, account, notifications, voice, app lock and diagnostics.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var showsDiagnostics = false
    @State private var confirmSignOut = false
    @State private var showsPassword = false

    private var push: PushController { model.push }

    var body: some View {
        @Bindable var settings = model.settings
        Form {
            Section {
                SettingsRow("Gateway", value: model.connection.endpoint?.origin ?? "Demo", mono: true)
                // The account and what it is allowed to do, in that order: the
                // role is the reason the rows under it differ (A24).
                AccountRow(user: model.connection.user)
                if !model.connection.gatewayVersion.isEmpty {
                    SettingsRow("Gateway version", value: model.connection.gatewayVersion)
                }
                // The admin's password is the gateway's own `RC_PASSWORD`, so
                // there is nothing here that could change it.
                if !model.connection.isAdmin {
                    Button("Change password") { showsPassword = true }
                        .font(Theme.Text.label)
                        .settingsRowLayout()
                        .accessibilityIdentifier("settings.changePassword")
                }
                if model.connection.isAdmin {
                    NavigationLink {
                        UsersView().environment(model)
                    } label: {
                        Text("Users").font(Theme.Text.label)
                    }
                    .settingsRowLayout()
                    .accessibilityIdentifier("settings.users")
                }
                Button("Sign out", role: .destructive) { confirmSignOut = true }
                    .font(Theme.Text.label)
                    .settingsRowLayout()
                    .accessibilityIdentifier("settings.signOut")
            } header: {
                FieldLabel("Account")
            }

            Section {
                Toggle("Notify me", isOn: $settings.notificationsEnabled)
                    .font(Theme.Text.label)
                    .settingsRowLayout()
                    // The app's own alerts need no gateway, so the switch works
                    // in the demo too wherever the system can raise one.
                    .disabled(!push.isSupported)
                    .accessibilityIdentifier("settings.notifications")
                SettingsRow("Status", value: push.statusText)
                if push.authorization == .denied {
                    Button("Open iOS Settings") { push.openSystemSettings() }
                        .font(Theme.Text.label)
                        .settingsRowLayout()
                }
            } header: {
                FieldLabel("Notifications")
            } footer: {
                SettingsFooter(L10n.string("One switch for the gateway's notifications and the alerts this app raises while it is open: each says which device and session needs you, and nothing else. No prompt text, output or file contents leave the gateway."))
            }

            Section {
                Picker("Transcribe", selection: $settings.voiceBackend) {
                    ForEach(VoiceBackend.allCases, id: \.self) { backend in
                        Text(backend.title).tag(backend)
                    }
                }
                .font(Theme.Text.label)
                .settingsRowLayout()
                .accessibilityIdentifier("settings.voiceBackend")
                .disabled(!model.connection.stt.enabled && settings.voiceBackend == .onDevice)
                // Named for what it is: the interface language is a setting of
                // its own two groups below, and two rows called "Language" on
                // one screen is a riddle rather than a preference.
                Picker("Dictation language", selection: $settings.voiceLanguage) {
                    Text("Automatic").tag("auto")
                    ForEach(languageCodes, id: \.self) { code in
                        Text(languageName(code)).tag(code)
                    }
                }
                .font(Theme.Text.label)
                .settingsRowLayout()
                // Amendment A29: dictation is the setting above; what happens
                // to the words afterwards is the setting below it.
                PolishSettingsRows()
            } header: {
                FieldLabel("Voice")
            } footer: {
                VStack(alignment: .leading, spacing: Theme.Space.tight) {
                    SettingsFooter(voiceFooter)
                    SettingsFooter(polishFooter)
                }
            }

            Section {
                Picker("Language", selection: $settings.language) {
                    ForEach(InterfaceLanguage.allCases, id: \.self) { language in
                        // Each name in its own script, so the one you want is
                        // recognisable from inside the language you cannot read.
                        Text(verbatim: language.title).tag(language)
                    }
                }
                // Two options, both one word: the group's own caption names the
                // setting, so a second "Language" on the row would say it twice.
                .pickerStyle(.segmented)
                .labelsHidden()
                .settingsRowLayout()
                .accessibilityIdentifier("settings.language")
            } header: {
                FieldLabel("Language")
            }

            Section {
                Picker("Detail", selection: $settings.timelineDetail) {
                    ForEach(TimelineDetail.allCases, id: \.self) { level in
                        Text(level.title).tag(level)
                    }
                }
                .font(Theme.Text.label)
                .settingsRowLayout()
                .accessibilityIdentifier("settings.timelineDetail")
            } header: {
                FieldLabel("Timeline")
            } footer: {
                SettingsFooter(TimelineDetail.footnote)
            }

            Section {
                Toggle("Require Face ID", isOn: $settings.appLockEnabled)
                    .font(Theme.Text.label)
                    .settingsRowLayout()
                    .accessibilityIdentifier("settings.appLock")
            } header: {
                FieldLabel("App lock")
            } footer: {
                SettingsFooter(L10n.string("Unlock with Face ID, Touch ID or your passcode when the app returns from the background."))
            }

            Section {
                SettingsRow("Version", value: Self.appVersion)
                SettingsRow("Protocol", value: "v\(RemoteProtocol.version)")
                Button("Diagnostics") { showsDiagnostics = true }
                    .font(Theme.Text.label)
                    .settingsRowLayout()
                    .accessibilityIdentifier("settings.diagnostics")
            } header: {
                FieldLabel("About")
            }
        }
        .scrollContentBackground(.hidden)
        .pageBackground()
        .navigationTitle("Settings")
        .onAppear { model.attachPush() }
        .onChange(of: settings.notificationsEnabled) { _, enabled in
            push.setEnabled(enabled)
            if enabled { push.requestAuthorizationIfNeeded() }
        }
        .sheet(isPresented: $showsDiagnostics) {
            DiagnosticsView(report: report)
        }
        .sheet(isPresented: $showsPassword) {
            PasswordSheet().environment(model)
        }
        .confirmationDialog("Sign out of this gateway?", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { Task { await model.signOut() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Cached sessions and drafts for this account are removed from this device. Nothing changes on your machines.")
        }
    }

    private var languageCodes: [String] {
        let offered = model.connection.stt.languages.filter { $0 != "auto" }
        return offered.isEmpty ? ["en", "zh", "ja", "de", "fr", "es"] : offered
    }

    private func languageName(_ code: String) -> String {
        Locale.current.localizedString(forLanguageCode: code) ?? code
    }

    private var voiceFooter: String {
        if model.settings.voiceBackend == .gateway && !model.connection.stt.enabled {
            return L10n.string(
                "This gateway has no transcription service configured, so dictation falls back to on-device recognition.")
        }
        return model.settings.voiceBackend.explanation
    }

    /// Amendment A29: what polishing sends, and when. A gateway with no polish
    /// model says so instead, under a switch that cannot be turned on.
    private var polishFooter: String {
        guard model.connection.polish.enabled else {
            return L10n.string("This gateway has no polish model configured")
        }
        return L10n.string("When this is on, what you dictated and the last few messages of the conversation are sent to the model this gateway is configured with. Nothing is sent while it is off.")
    }

    private var report: String {
        let system = ProcessInfo.processInfo.operatingSystemVersion
        #if os(iOS)
        let platform = "iOS"
        #else
        let platform = "macOS preview host"
        #endif
        return model.settings.diagnosticReport(
            appVersion: Self.appVersion, platform: platform,
            osVersion: "\(system.majorVersion).\(system.minorVersion).\(system.patchVersion)",
            phase: model.connection.phase,
            deviceCount: model.connection.devices.count,
            sessionCount: model.connection.sessions.count,
            sttEnabled: model.connection.stt.enabled,
            isDemo: model.isDemo)
    }

    /// Amendment A31: the same number the gateway's minimum is measured
    /// against, read in one place so the two can never disagree.
    static var appVersion: String { AppBuild.version }
}

/// Who is signed in, with what they are allowed to do under it (A24). Two lines
/// rather than two rows: the role explains the rows below and is not a setting.
struct AccountRow: View {
    let user: UserIdentity

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.medium) {
            Text("Signed in as").font(Theme.Text.label).foregroundStyle(Theme.ink)
            Spacer(minLength: Theme.Space.small)
            VStack(alignment: .trailing, spacing: 2) {
                Text(user.username)
                    .font(Theme.Text.meta)
                    .foregroundStyle(Theme.inkSecondary)
                    .lineLimit(1)
                Text(user.role.title)
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.inkSecondary)
                    .accessibilityIdentifier("settings.role")
            }
        }
        .settingsRowLayout()
        .accessibilityElement(children: .combine)
    }
}

/// Label left, value right, one line each. The value is quiet: a settings
/// screen is a list of labels, not a table of two columns.
struct SettingsRow: View {
    let label: LocalizedStringKey
    let value: String
    var mono = false

    init(_ label: LocalizedStringKey, value: String, mono: Bool = false) {
        self.label = label
        self.value = value
        self.mono = mono
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.medium) {
            Text(label).font(Theme.Text.label).foregroundStyle(Theme.ink)
            Spacer(minLength: Theme.Space.small)
            Text(value)
                .font(mono ? Theme.Text.metaMono : Theme.Text.meta)
                .foregroundStyle(Theme.inkSecondary)
                .lineLimit(1)
                .truncationMode(mono ? .middle : .tail)
        }
        .settingsRowLayout()
        .accessibilityElement(children: .combine)
    }
}

/// The sentence under a group. Caption weight, secondary, never a box.
struct SettingsFooter: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(Theme.Text.caption)
            .foregroundStyle(Theme.inkSecondary)
            .padding(.top, Theme.Space.hair)
    }
}

extension View {
    /// The inset every settings row shares, so labels, toggles and pickers line
    /// up and the surface has room around them.
    func settingsRowLayout() -> some View {
        listRowBackground(Theme.surface)
            .listRowInsets(EdgeInsets(top: 12, leading: Theme.Space.medium,
                                      bottom: 12, trailing: Theme.Space.medium))
            .frame(minHeight: 28)
    }
}

/// Read the report first, then decide whether to share it.
struct DiagnosticsView: View {
    let report: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.medium) {
                    Text("This is everything the report contains. Read it before you share it.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSecondary)
                    Text(report)
                        .font(Theme.mono)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("diagnostics.report")
                    ShareLink(item: report) {
                        Label("Share this report", systemImage: "square.and.arrow.up")
                            .frame(minHeight: Theme.Touch.minimum)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(Theme.Space.page)
            }
            .pageBackground()
            .navigationTitle("Diagnostics")
            .inlineNavigationTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .sheetSize()
    }
}

#Preview("Settings") {
    DemoPreview { NavigationStack { SettingsView() } }
}

#Preview("Diagnostics") {
    DiagnosticsView(report: """
    Remote Control for iOS — diagnostic snapshot
    App: 0.1.0
    Platform: iOS
    Protocol: v1
    Cache schema: v1
    Mode: offline demo
    Connection: connected
    """)
}
