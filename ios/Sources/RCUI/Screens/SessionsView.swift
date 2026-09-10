import SwiftUI
import RCCore

/// Every session on every device, grouped by machine, newest attention first.
struct SessionsView: View {
    @Environment(AppModel.self) private var model
    @State private var isCreating = false

    var body: some View {
        @Bindable var sessions = model.sessions
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

            ForEach(model.sessions.grouped(model.connection.sessions, devices: model.connection.devices)) { group in
                Section {
                    ForEach(group.sessions) { session in
                        Button {
                            Task { await model.open(session) }
                        } label: {
                            SessionRow(session: session)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Theme.surface)
                        .accessibilityIdentifier("session.\(session.sessionID)")
                        .swipeActions(edge: .trailing) {
                            Button {
                                Task {
                                    await model.connection.setArchived(!session.archived, session: session)
                                }
                            } label: {
                                Label(session.archived ? "Unarchive" : "Archive",
                                      systemImage: session.archived ? "tray.and.arrow.up" : "archivebox")
                            }
                            .tint(Theme.inkSecondary)
                        }
                    }
                } header: {
                    HStack(spacing: Theme.Space.tight) {
                        Circle()
                            .fill(group.online ? Theme.running : Theme.resting)
                            .frame(width: 6, height: 6)
                        Text(group.deviceName.uppercased())
                            .font(.caption2.weight(.semibold))
                            .kerning(0.6)
                    }
                    .foregroundStyle(Theme.inkSecondary)
                }
            }

            if model.connection.sessions.isEmpty {
                EmptyStateView(symbol: "bubble.left.and.text.bubble.right",
                               title: "No sessions yet",
                               message: model.connection.devices.isEmpty
                                   ? "Add a device first, then start a session on it."
                                   : "Start a session to drive an agent from here.")
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
                .font(.footnote)
                .foregroundStyle(Theme.inkSecondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Theme.Space.tight)
                .barBackground()
                .accessibilityIdentifier("sessions.summary")
        }
        .toolbar {
            ToolbarItem(placement: .trailingBar) {
                Toggle(isOn: $sessions.showsArchived) {
                    Image(systemName: sessions.showsArchived ? "archivebox.fill" : "archivebox")
                }
                .toggleStyle(.button)
                .accessibilityLabel("Show archived sessions")
            }
        }
        .sheet(isPresented: $isCreating) {
            NewSessionSheet().environment(model)
        }
    }

    private func archive(_ session: Session) async {
        guard let channel = model.connection.channel else { return }
        _ = try? await channel.request(.archive(sessionID: session.sessionID, archived: !session.archived))
    }
}

/// Status dot, title, `device · folder`, then state and relative time.
struct SessionRow: View {
    let session: Session

    var body: some View {
        HStack(alignment: .top, spacing: Theme.Space.small) {
            StatusDot(state: session.state).padding(.top, 6)
            VStack(alignment: .leading, spacing: 3) {
                Text(session.title.isEmpty ? "Untitled session" : session.title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                CodeText(session.cwd)
            }
            Spacer(minLength: Theme.Space.small)
            VStack(alignment: .trailing, spacing: 3) {
                Text(session.state.label)
                    .font(.footnote)
                    .foregroundStyle(session.state.isBlockedOnUser ? Theme.attention : Theme.inkSecondary)
                Text(RelativeTime.short(since: session.updatedAt))
                    .font(.caption)
                    .foregroundStyle(Theme.inkSecondary)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(session.title), \(session.state.label), \(session.cwd)")
    }
}

#Preview("Sessions") {
    DemoPreview { NavigationStack { SessionsView() } }
}
