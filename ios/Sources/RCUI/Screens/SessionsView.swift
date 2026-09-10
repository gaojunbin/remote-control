import SwiftUI
import RCCore

/// Every session on every device: Active grouped by machine, then one collapsed
/// Archive for the ones nothing owns any more.
struct SessionsView: View {
    @Environment(AppModel.self) private var model
    @State private var isCreating = false

    var body: some View {
        @Bindable var sessions = model.sessions
        let list = model.sessions.list(model.connection.sessions, devices: model.connection.devices)
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

            ForEach(list.active) { group in
                Section {
                    if group.sessions.isEmpty {
                        // A caption on the canvas, not a surface with nothing
                        // in it: an empty box is louder than the sentence.
                        Text("No open sessions")
                            .font(Theme.Text.meta)
                            .foregroundStyle(Theme.inkSecondary)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 0, leading: Theme.Space.medium,
                                                      bottom: Theme.Space.small,
                                                      trailing: Theme.Space.medium))
                            .accessibilityIdentifier("sessions.empty.\(group.id)")
                    }
                    ForEach(group.sessions) { session in
                        row(session)
                    }
                } header: {
                    ListGroupHeader(group.deviceName, dot: group.online ? Theme.running : Theme.resting)
                }
            }

            if !list.archive.isEmpty {
                Section {
                    if model.sessions.showsArchiveContents(of: list) {
                        ForEach(list.archive) { archived in
                            row(archived.session, device: archived.deviceName)
                        }
                    }
                } header: {
                    archiveHeader(list)
                }
            }

            if list.isEmpty {
                EmptyStateView(symbol: "bubble.left.and.text.bubble.right",
                               title: emptyTitle(searching: list.isSearching),
                               message: emptyMessage(searching: list.isSearching))
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
            ToolbarItem(placement: .trailingBar) {
                Button {
                    sessions.showsArchived.toggle()
                } label: {
                    Image(systemName: sessions.showsArchived ? "archivebox.fill" : "archivebox")
                        .font(.body)
                }
                .buttonStyle(.plain)
                .foregroundStyle(sessions.showsArchived ? Theme.ink : Theme.inkSecondary)
                .frame(minWidth: Theme.Touch.minimum, minHeight: Theme.Touch.minimum)
                .accessibilityLabel("Show archived sessions")
                .accessibilityAddTraits(sessions.showsArchived ? [.isSelected] : [])
                .accessibilityIdentifier("sessions.showArchived")
            }
        }
        .sheet(isPresented: $isCreating) {
            NewSessionSheet().environment(model)
        }
    }

    private func row(_ session: Session, device: String? = nil) -> some View {
        Button {
            Task { await model.open(session) }
        } label: {
            SessionRow(session: session, device: device)
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

    /// One tap opens or closes the Archive, and the choice is remembered. A
    /// search that found something inside opens it and says so by disabling the
    /// tap rather than fighting the reader for it.
    private func archiveHeader(_ list: SessionList) -> some View {
        let open = model.sessions.showsArchiveContents(of: list)
        return Button {
            model.sessions.isArchiveExpanded.toggle()
        } label: {
            ListGroupHeader("Archive · \(list.archiveCount)") {
                Image(systemName: "chevron.down")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Theme.inkSecondary)
                    .rotationEffect(.degrees(open ? 0 : -90))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(list.forcesArchiveOpen)
        .accessibilityIdentifier("sessions.archive")
        .accessibilityLabel("Archive, \(list.archiveCount) sessions")
        .accessibilityHint(open ? "Collapses the archive" : "Expands the archive")
    }

    private func emptyTitle(searching: Bool) -> String {
        searching ? "Nothing matches" : "No sessions yet"
    }

    private func emptyMessage(searching: Bool) -> String {
        if searching { return "No session title, folder or agent matches that." }
        return model.connection.devices.isEmpty
            ? "Add a device first, then start a session on it."
            : "Start a session to drive an agent from here."
    }
}

/// Title and time on one line, then a dot, a word and the folder on the next.
/// Nothing is right-aligned into a column, because a column of statuses reads
/// as a table.
struct SessionRow: View {
    let session: Session
    var device: String?

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
                StatusLabel(state: session.state, text: session.statusLabel)
                if let device {
                    separator
                    Text(device)
                        .font(Theme.Text.caption)
                        .foregroundStyle(Theme.inkSecondary)
                        .lineLimit(1)
                }
                separator
                CodeText(session.cwd, font: Theme.Text.metaMono)
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
        guard let device else { return "\(title), \(session.statusLabel), \(session.cwd)" }
        return "\(title), \(session.statusLabel), \(device), \(session.cwd)"
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
