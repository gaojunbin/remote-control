import SwiftUI
import RCCore

/// Device, agent, working directory and git.
struct NewSessionSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var deviceID = ""
    @State private var agentID = ""
    @State private var cwd = ""
    @State private var modelID = ""
    @State private var effort = ""
    @State private var permissionMode = ""
    @State private var speed = SpeedChange.standard
    @State private var worktree = false
    @State private var recent: [RecentDirectory] = []
    @State private var git: GitStatus?
    @State private var isBrowsing = false
    @State private var isStarting = false
    @State private var error: String?

    private var device: Device? { model.connection.device(deviceID) }
    private var agent: AgentInfo? { device?.agent(agentID) }

    var body: some View {
        NavigationStack {
            Form {
                deviceSection
                agentSection
                settingsSections
                directorySection
                gitSection
                if let error {
                    Text(error).font(.footnote).foregroundStyle(Theme.danger)
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle("New session")
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    Task { await start() }
                } label: {
                    HStack(spacing: Theme.Space.small) {
                        if isStarting { ProgressView().tint(Theme.onAccent) }
                        Text("Start session")
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(isStarting || deviceID.isEmpty || agentID.isEmpty || cwd.trimmed.isEmpty)
                .padding(.horizontal, Theme.Space.page)
                .padding(.vertical, Theme.Space.small)
                .barBackground()
                .accessibilityIdentifier("newsession.start")
            }
            .task { await prepare() }
            .sheet(isPresented: $isBrowsing) {
                DirectoryPicker(deviceID: deviceID) { path in
                    cwd = path
                    Task { await refreshGit() }
                }
                .environment(model)
            }
        }
        .sheetSize()
        .dismissesKeyboardOnBackgroundTap()
    }

    private var deviceSection: some View {
        Section {
            Picker("Device", selection: $deviceID) {
                ForEach(model.connection.onlineDevices) { device in
                    HStack {
                        Text(device.name)
                        if let latency = device.latencyMS {
                            Text("\(latency) ms").foregroundStyle(Theme.inkSecondary)
                        }
                    }
                    .tag(device.deviceID)
                }
            }
            .labelsHidden()
            .accessibilityIdentifier("newsession.device")
            .onChange(of: deviceID) { _, _ in Task { await prepareForDevice() } }
            if model.connection.onlineDevices.isEmpty {
                Text("No device is online. Add one from the Devices tab.")
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSecondary)
            }
        } header: {
            FieldLabel("Device")
        }
    }

    /// `docs/DESIGN.md` § "Agents": four agents do not fit a segmented control
    /// by name, so each segment carries the agent's logo alone and the line
    /// under the control names the one that is chosen. The logo is for the eye
    /// only — assistive technology reads the name.
    private var agentSection: some View {
        Section {
            Picker("Agent", selection: $agentID) {
                ForEach(device?.availableAgents ?? []) { info in
                    agentSegment(info)
                        .accessibilityLabel(info.displayName)
                        .tag(info.agent)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("newsession.agent")
            .onChange(of: agentID) { _, _ in adoptAgentDefaults() }
            if let agent {
                HStack(spacing: Theme.Space.tight) {
                    Text(agent.displayName)
                        .font(Theme.Text.meta)
                        .foregroundStyle(Theme.inkSecondary)
                    Text(subtitle(for: agent))
                        .font(Theme.mono)
                        .foregroundStyle(Theme.inkSecondary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("newsession.agentLine")
            }
        } header: {
            FieldLabel("Agent")
        }
    }

    /// A segmented control is drawn by UIKit, which takes an image or a word
    /// and nothing else, so the logo goes in bare and an agent with no vector
    /// falls back to the first letter of its id.
    @ViewBuilder
    private func agentSegment(_ info: AgentInfo) -> some View {
        if let image = AgentLogo.image(info.agent) {
            image.renderingMode(.template)
        } else {
            Text(AgentLabel.initial(info.agent))
        }
    }

    /// What the agent runs and how hard, in the order `docs/DESIGN.md` gives
    /// every form: model, effort, permissions, and the speed tier after them
    /// where the agent offers one (A21). Each list is the agent's own.
    @ViewBuilder
    private var settingsSections: some View {
        if let agent, !agent.models.isEmpty {
            Section {
                Picker("Model", selection: $modelID) {
                    ForEach(agent.models) { option in Text(option.label).tag(option.id) }
                }
                .accessibilityIdentifier("newsession.model")
            } header: { FieldLabel("Model") }
        }
        if let agent, agent.supports(.effort), !agent.efforts.isEmpty {
            Section {
                Picker("Effort", selection: $effort) {
                    ForEach(agent.efforts) { option in Text(option.label).tag(option.id) }
                }
                .accessibilityIdentifier("newsession.effort")
            } header: { FieldLabel("Effort") }
        }
        if let agent, !agent.permissionModes.isEmpty {
            Section {
                Picker("Permissions", selection: $permissionMode) {
                    ForEach(agent.permissionModes) { option in Text(option.label).tag(option.id) }
                }
                .accessibilityIdentifier("newsession.permissions")
            } header: { FieldLabel("Permissions") }
        }
        if let agent, !agent.speeds.isEmpty {
            Section {
                SpeedPicker(speeds: agent.speeds, selection: $speed)
                    .accessibilityIdentifier("newsession.speed")
            } header: { FieldLabel("Speed") }
        }
    }

    private var directorySection: some View {
        Section {
            TextField("~/dev/project", text: $cwd)
                .font(Theme.monoBody)
                .plainTextEntry()
                .frame(minHeight: Theme.Touch.minimum)
                .accessibilityIdentifier("newsession.cwd")
                .onSubmit { Task { await refreshGit() } }
            ForEach(recent) { entry in
                Button {
                    cwd = entry.path
                    Task { await refreshGit() }
                } label: {
                    HStack {
                        CodeText(entry.path, color: Theme.ink)
                        Spacer()
                        Text(RelativeTime.short(since: entry.lastUsed))
                            .font(.caption)
                            .foregroundStyle(Theme.inkSecondary)
                    }
                    .frame(minHeight: Theme.Touch.minimum)
                }
                .buttonStyle(.plain)
            }
        } header: {
            FieldLabel("Working directory") {
                Button("Browse") { isBrowsing = true }
                    .font(.caption.weight(.medium))
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.ink)
                    .accessibilityIdentifier("newsession.browse")
            }
        }
    }

    @ViewBuilder
    private var gitSection: some View {
        if let git, git.isRepo {
            Section {
                HStack(spacing: Theme.Space.small) {
                    Text(git.branch ?? "detached").font(Theme.monoBody).foregroundStyle(Theme.ink)
                    Text(gitDetail(git)).font(.footnote).foregroundStyle(Theme.inkSecondary)
                    Spacer()
                }
                if agent?.supports(.worktree) == true {
                    Toggle("Isolate in a worktree", isOn: $worktree)
                        .accessibilityIdentifier("newsession.worktree")
                }
            } header: {
                FieldLabel("Git")
            } footer: {
                if agent?.supports(.worktree) == true {
                    Text("A worktree gives the agent its own checkout, so your working copy stays untouched.")
                        .font(.caption)
                }
            }
        }
    }

    /// What the device detected, after the agent's name: the version it found
    /// and the model that agent would start on.
    private func subtitle(for agent: AgentInfo) -> String {
        [agent.version, agent.modelLabel(agent.defaultModel)]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    private func gitDetail(_ git: GitStatus) -> String {
        var parts: [String] = [L10n.string(git.dirty == true ? "dirty" : "clean")]
        if let ahead = git.ahead, ahead > 0 { parts.append(L10n.string("%lld ahead", ahead)) }
        if let behind = git.behind, behind > 0 { parts.append(L10n.string("%lld behind", behind)) }
        return parts.joined(separator: " · ")
    }

    private func prepare() async {
        if deviceID.isEmpty { deviceID = model.connection.onlineDevices.first?.deviceID ?? "" }
        await prepareForDevice()
    }

    private func prepareForDevice() async {
        agentID = device?.availableAgents.first?.agent ?? ""
        adoptAgentDefaults()
        worktree = false
        guard let channel = model.connection.channel, !deviceID.isEmpty else { return }
        if let listing = try? await channel.request(.dirs(deviceID: deviceID), as: DirectoryListing.self) {
            recent = listing.recent
            if cwd.isEmpty { cwd = listing.recent.first?.path ?? listing.path }
        }
        await refreshGit()
    }

    /// The agent's own defaults, so a sheet opened and sent untouched asks for
    /// exactly what the device would have chosen on its own.
    private func adoptAgentDefaults() {
        modelID = agent?.defaultModel ?? ""
        effort = agent?.defaultEffort ?? ""
        permissionMode = agent?.defaultPermissionMode ?? ""
        speed = .standard
    }

    private func refreshGit() async {
        guard let channel = model.connection.channel, !cwd.trimmed.isEmpty else { return }
        git = try? await channel.request(.git(deviceID: deviceID, path: cwd.trimmed), as: GitStatus.self)
        if git?.isRepo != true { worktree = false }
    }

    private func start() async {
        guard let channel = model.connection.channel, let agent else { return }
        isStarting = true
        error = nil
        defer { isStarting = false }
        do {
            let request = GatewayRequest.createSession(
                deviceID: deviceID, agent: agentID, cwd: cwd.trimmed,
                model: modelID.isEmpty ? nil : modelID,
                permissionMode: permissionMode.isEmpty ? nil : permissionMode,
                effort: effort.isEmpty ? nil : effort,
                speed: speed == .standard ? nil : speed,
                worktree: agent.supports(.worktree) ? worktree : nil)
            let result = try await channel.request(request, as: SessionResult.self)
            dismiss()
            await model.open(result.session)
        } catch {
            self.error = model.connection.message(for: error)
        }
    }
}

/// Amendment A21: the standard speed and every tier the agent lists, as one
/// list picker. Forms use it; the composer's card uses the lightning toggle.
struct SpeedPicker: View {
    let speeds: [AgentOption]
    @Binding var selection: SpeedChange

    var body: some View {
        Picker("Speed", selection: $selection) {
            Text("Standard").tag(SpeedChange.standard)
            ForEach(speeds) { option in Text(option.label).tag(SpeedChange.tier(option.id)) }
        }
    }
}

#Preview("New session") {
    DemoPreview { NewSessionSheet() }
}
