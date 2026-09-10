import SwiftUI
import RCCore

/// Gateway, account, notifications, voice, app lock and diagnostics.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var showsDiagnostics = false
    @State private var confirmSignOut = false

    private var push: PushController { model.push }

    var body: some View {
        @Bindable var settings = model.settings
        Form {
            Section {
                LabeledContent("Gateway") {
                    Text(model.connection.endpoint?.origin ?? "Demo")
                        .font(Theme.mono)
                        .foregroundStyle(Theme.inkSecondary)
                }
                LabeledContent("Signed in as") {
                    Text(model.connection.username).foregroundStyle(Theme.inkSecondary)
                }
                if !model.connection.gatewayVersion.isEmpty {
                    LabeledContent("Gateway version") {
                        Text(model.connection.gatewayVersion).foregroundStyle(Theme.inkSecondary)
                    }
                }
                Button("Sign out", role: .destructive) { confirmSignOut = true }
                    .frame(minHeight: Theme.Touch.minimum)
                    .accessibilityIdentifier("settings.signOut")
            } header: {
                FieldLabel("Account")
            }

            Section {
                Toggle("Notify me", isOn: $settings.notificationsEnabled)
                    .disabled(!push.isSupported || model.isDemo)
                    .accessibilityIdentifier("settings.notifications")
                LabeledContent("Status") {
                    Text(push.statusText).foregroundStyle(Theme.inkSecondary)
                }
                if push.authorization == .denied {
                    Button("Open iOS Settings") { push.openSystemSettings() }
                        .frame(minHeight: Theme.Touch.minimum)
                }
            } header: {
                FieldLabel("Notifications")
            } footer: {
                Text("A notification says which device and session needs you, and nothing else. No prompt text, output or file contents leave the gateway.")
                    .font(.caption)
            }

            Section {
                Picker("Transcribe", selection: $settings.voiceBackend) {
                    ForEach(VoiceBackend.allCases, id: \.self) { backend in
                        Text(backend.title).tag(backend)
                    }
                }
                .accessibilityIdentifier("settings.voiceBackend")
                .disabled(!model.connection.stt.enabled && settings.voiceBackend == .onDevice)
                Picker("Language", selection: $settings.voiceLanguage) {
                    Text("Automatic").tag("auto")
                    ForEach(languageCodes, id: \.self) { code in
                        Text(languageName(code)).tag(code)
                    }
                }
            } header: {
                FieldLabel("Voice")
            } footer: {
                Text(voiceFooter).font(.caption)
            }

            Section {
                Toggle("Require Face ID", isOn: $settings.appLockEnabled)
                    .accessibilityIdentifier("settings.appLock")
            } header: {
                FieldLabel("App lock")
            } footer: {
                Text("Unlock with Face ID, Touch ID or your passcode when the app returns from the background.")
                    .font(.caption)
            }

            Section {
                LabeledContent("Version") {
                    Text(Self.appVersion).foregroundStyle(Theme.inkSecondary)
                }
                LabeledContent("Protocol") {
                    Text("v\(RemoteProtocol.version)").foregroundStyle(Theme.inkSecondary)
                }
                Button("Diagnostics") { showsDiagnostics = true }
                    .frame(minHeight: Theme.Touch.minimum)
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
            return "This gateway has no transcription service configured, so dictation falls back to on-device recognition."
        }
        return model.settings.voiceBackend.explanation
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

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
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
