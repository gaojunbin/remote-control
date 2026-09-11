import SwiftUI
import RCCore

/// Every session, grouped by the machine it runs on: what that machine still
/// holds, then its own collapsed Archive underneath.
struct SessionsView: View {
    @Environment(AppModel.self) private var model
    @State private var isCreating = false

    var body: some View {
        @Bindable var sessions = model.sessions
        let groups = model.sessions.groups(model.connection.sessions, devices: model.connection.devices)
        List {
            Section {
                Button {
                    isCreating = true
                } label: {
                    Label("New session", systemImage: "plus")
                        .font(.body.weight(.medium))
                }
                .buttonStyle(PrimaryButtonStyle())
                .listRowInsets(EdgeInsets(top: Theme.Space.small, leading: Theme.Space.page,
                                          bottom: Theme.Space.small, trailing: Theme.Space.page))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .disabled(model.connection.onlineDevices.isEmpty)
                .accessibilityIdentifier("sessions.new")
            }

            ForEach(groups) { group in
                Section {
                    if !group.collapsed {
                        ForEach(group.active) { session in
                            row(session, online: group.online)
                        }
                        if !group.archive.isEmpty {
                            archiveHeader(group)
                            if group.archiveExpanded {
                                ForEach(group.archive) { session in
                                    row(session, online: group.online)
                                }
                            }
                        }
                    }
                } header: {
                    deviceHeader(group)
                }
            }

            if groups.isEmpty {
                EmptyStateView(symbol: "bubble.left.and.text.bubble.right",
                               title: emptyTitle, message: emptyMessage)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
        }
        .groupedList()
        .scrollContentBackground(.hidden)
        .pageBackground()
        .navigationTitle("Sessions")
        .searchable(text: $sessions.searchText, prompt: "Search sessions")
        .safeAreaInset(edge: .top, spacing: 0) {
            ConnectionSummary(phase: model.connection.phase, isDemo: model.isDemo) {
                Task { await model.connection.reconnect() }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            Text(model.connection.inventorySummary)
                .font(Theme.Text.caption)
                .foregroundStyle(Theme.inkSecondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Theme.Space.tight)
                .barBackground()
                .accessibilityIdentifier("sessions.summary")
        }
        .toolbar {
            ToolbarItem(placement: .trailingBar) { agentFilter }
        }
        .sheet(isPresented: $isCreating) {
            NewSessionSheet().environment(model)
        }
    }

    /// All, then the agents the list actually contains. The choice is a view of
    /// this list rather than a setting, so it is not remembered.
    @ViewBuilder
    private var agentFilter: some View {
        let options = model.sessions.agentOptions(model.connection.sessions)
        if !options.isEmpty {
            Menu {
                filterChoice(nil, label: "All")
                ForEach(options, id: \.self) { agent in
                    filterChoice(agent, label: AgentLabel.name(agent))
                }
            } label: {
                HStack(spacing: Theme.Space.hair + 2) {
                    Image(systemName: "line.3.horizontal.decrease")
                    if let agent = model.sessions.agentFilter {
                        Text(AgentLabel.name(agent)).font(Theme.Text.meta)
                    }
                }
                .foregroundStyle(model.sessions.agentFilter == nil ? Theme.inkSecondary : Theme.ink)
                .frame(minHeight: Theme.Touch.minimum)
            }
            .accessibilityLabel("Filter by agent")
            .accessibilityValue(model.sessions.agentFilter.map(AgentLabel.name) ?? "All")
            .accessibilityIdentifier("sessions.agentFilter")
        }
    }

    private func filterChoice(_ agent: String?, label: String) -> some View {
        Button {
            model.sessions.agentFilter = agent
        } label: {
            if model.sessions.agentFilter == agent {
                Label(label, systemImage: "checkmark")
            } else {
                Text(label)
            }
        }
        .accessibilityIdentifier("sessions.agentFilter.\(agent ?? "all")")
    }

    private func row(_ session: Session, online: Bool) -> some View {
        Button {
            Task { await model.open(session) }
        } label: {
            SessionRow(session: session, online: online)
        }
        .buttonStyle(.plain)
        .sessionRowLayout()
        .accessibilityIdentifier("session.\(session.sessionID)")
        .swipeActions(edge: .trailing) {
            Button {
                Task { await model.connection.setArchived(!session.archived, session: session) }
            } label: {
                Label(session.archived ? "Unarchive" : "Archive",
                      systemImage: session.archived ? "tray.and.arrow.up" : "archivebox")
            }
            .tint(Theme.inkSecondary)
        }
    }

    /// The machine's name exactly as it reported it, its online dot, and a
    /// chevron. One tap folds the whole group away, and that choice is
    /// remembered per machine.
    private func deviceHeader(_ group: DeviceGroup) -> some View {
        Button {
            model.sessions.toggleCollapsed(group.id)
        } label: {
            HStack(spacing: Theme.Space.tight) {
                Circle()
                    .fill(group.online ? Theme.running : Theme.resting)
                    .frame(width: 6, height: 6)
                    .accessibilityHidden(true)
                Text(group.name)
                    .font(Theme.Text.title)
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                Spacer(minLength: Theme.Space.tight)
                chevron(open: !group.collapsed)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .textCase(nil)
        .accessibilityIdentifier("sessions.device.\(group.id)")
        .accessibilityLabel("\(group.name), \(group.online ? "online" : "offline")")
        .accessibilityHint(group.collapsed ? "Expands this device" : "Collapses this device")
    }

    /// The device's own Archive: what nothing owns any more, plus what was
    /// archived by hand. Collapsed until it is asked for, or until a search
    /// finds something inside it.
    private func archiveHeader(_ group: DeviceGroup) -> some View {
        Button {
            model.sessions.toggleArchive(group.id)
        } label: {
            HStack(spacing: Theme.Space.tight) {
                Text("Archive · \(group.archive.count)")
                    .font(Theme.Text.meta)
                    .foregroundStyle(Theme.inkSecondary)
                Spacer(minLength: Theme.Space.tight)
                chevron(open: group.archiveExpanded)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(Theme.surface)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 9, leading: Theme.Space.medium,
                                  bottom: 9, trailing: Theme.Space.medium))
        .accessibilityIdentifier("sessions.archive.\(group.id)")
        .accessibilityLabel("Archive, \(group.archive.count) sessions on \(group.name)")
        .accessibilityHint(group.archiveExpanded ? "Collapses the archive" : "Expands the archive")
    }

    private func chevron(open: Bool) -> some View {
        Image(systemName: "chevron.down")
            .font(.caption2.weight(.semibold))
            .foregroundStyle(Theme.inkSecondary)
            .rotationEffect(.degrees(open ? 0 : -90))
    }

    private var emptyTitle: String {
        model.sessions.searchText.trimmed.isEmpty ? "No sessions yet" : "Nothing matches"
    }

    private var emptyMessage: String {
        if !model.sessions.searchText.trimmed.isEmpty {
            return "No session title, folder or agent matches that."
        }
        if let agent = model.sessions.agentFilter {
            return "No session on any device is running \(AgentLabel.name(agent))."
        }
        return model.connection.devices.isEmpty
            ? "Add a device first, then start a session on it."
            : "Start a session to drive an agent from here."
    }
}

/// Title and time on one line, then the agent, a dot, a word and the folder on
/// the next. Nothing is right-aligned into a column, because a column of
/// statuses reads as a table.
struct SessionRow: View {
    let session: Session
    /// A session on a machine that is not reachable shows a grey dot whatever
    /// it last reported, so the row never claims work is under way.
    let online: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.small) {
                Text(session.title.isEmpty ? "Untitled session" : session.title)
                    .font(Theme.Text.title)
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Text(RelativeTime.short(since: session.updatedAt))
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.inkSecondary)
            }
            HStack(spacing: Theme.Space.tight) {
                // The agent and the status word never shorten; the path is what
                // gives way, and it truncates from the head so the folder stays.
                AgentChip(agent: session.agent)
                StatusLabel(tone: session.dotTone(online: online), text: session.statusLabel)
                if session.archived {
                    separator
                    Text("Archived")
                        .font(Theme.Text.caption)
                        .foregroundStyle(Theme.inkSecondary)
                }
                separator
                CodeText(session.cwd, font: Theme.Text.metaMono)
                    .layoutPriority(-1)
                Spacer(minLength: 0)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(label)
    }

    private var separator: some View {
        Text("·").font(Theme.Text.caption).foregroundStyle(Theme.inkSecondary)
    }

    private var label: String {
        let title = session.title.isEmpty ? "Untitled session" : session.title
        var parts = [title, session.agentLabel, session.statusLabel]
        if session.archived { parts.append("archived") }
        parts.append(session.cwd)
        return parts.joined(separator: ", ")
    }
}

extension View {
    /// Rows sit on one soft surface with room to breathe and no rule between
    /// them: 14 pt of vertical space already tells one from the next.
    func sessionRowLayout() -> some View {
        listRowBackground(Theme.surface)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 14, leading: Theme.Space.medium,
                                      bottom: 14, trailing: Theme.Space.medium))
    }
}

#Preview("Sessions") {
    DemoPreview { NavigationStack { SessionsView() } }
}
