import SwiftUI
import RCCore

/// Device, agent, working directory and git.
struct NewSessionSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var deviceID = ""
    @State private var agentID = ""
    @State private var cwd = ""
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

    private var agentSection: some View {
        Section {
            Picker("Agent", selection: $agentID) {
                ForEach(device?.availableAgents ?? []) { info in
                    Text(info.displayName).tag(info.agent)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("newsession.agent")
            if let agent {
                Text(subtitle(for: agent))
                    .font(Theme.mono)
                    .foregroundStyle(Theme.inkSecondary)
            }
        } header: {
            FieldLabel("Agent")
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

    private func subtitle(for agent: AgentInfo) -> String {
        let version = agent.version.map { "\(agent.agent) \($0)" } ?? agent.agent
        guard let model = agent.modelLabel(agent.defaultModel) else { return version }
        return "\(version) · \(model)"
    }

    private func gitDetail(_ git: GitStatus) -> String {
        var parts: [String] = [git.dirty == true ? "dirty" : "clean"]
        if let ahead = git.ahead, ahead > 0 { parts.append("\(ahead) ahead") }
        if let behind = git.behind, behind > 0 { parts.append("\(behind) behind") }
        return parts.joined(separator: " · ")
    }

    private func prepare() async {
        if deviceID.isEmpty { deviceID = model.connection.onlineDevices.first?.deviceID ?? "" }
        await prepareForDevice()
    }

    private func prepareForDevice() async {
        agentID = device?.availableAgents.first?.agent ?? ""
        worktree = false
        guard let channel = model.connection.channel, !deviceID.isEmpty else { return }
        if let listing = try? await channel.request(.dirs(deviceID: deviceID), as: DirectoryListing.self) {
            recent = listing.recent
            if cwd.isEmpty { cwd = listing.recent.first?.path ?? listing.path }
        }
        await refreshGit()
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
                model: agent.defaultModel, permissionMode: agent.defaultPermissionMode,
                effort: agent.defaultEffort,
                worktree: agent.supports(.worktree) ? worktree : nil)
            let result = try await channel.request(request, as: SessionResult.self)
            dismiss()
            await model.open(result.session)
        } catch {
            self.error = model.connection.message(for: error)
        }
    }
}

#Preview("New session") {
    DemoPreview { NewSessionSheet() }
}
