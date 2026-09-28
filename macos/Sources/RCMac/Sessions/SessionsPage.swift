import RCCore
import SwiftUI

/// `/sessions` — `web/src/features/sessions/SessionsPage.tsx`: every session
/// across every device, one collapsible group per device with its active rows
/// and then its own folded Archive, a search, the agent and device filters, the
/// dot legend once above the list, and New session in the right-hand drawer.
/// ⌘N lands here and opens the drawer.
public struct SessionsPage: View {
    @Environment(MacAppModel.self) private var model
    @Environment(\.previewStage) private var stage
    @State private var query = ""
    /// One device, or all of them. A view of the list, never remembered.
    @State private var deviceFilter: String?
    @State private var creating: NewSessionForm?

    public init() {}

    public var body: some View {
        @Bindable var sessions = model.sessions
        let devices = DeviceOrder.byName(model.connection.devices)
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let groups = SessionLayout.build(sessions: model.connection.sessions, devices: devices,
                                         deviceFilter: deviceFilter, agentFilter: sessions.agentFilter,
                                         query: needle, collapsedDevices: sessions.collapsedDevices,
                                         archiveExpanded: sessions.expandedArchives)
        VStack(alignment: .leading, spacing: 0) {
            PageHead(S.sessions.title) {
                Btn(S.sessions.new, icon: .plus, variant: .primary, action: openNewSession)
            }
            SessionsToolbar(query: $query, agentFilter: $sessions.agentFilter, deviceFilter: $deviceFilter,
                            agents: SessionLayout.agents(in: model.connection.sessions), devices: devices,
                            openFilter: stagedFilter)
                .padding(.bottom, Space.sp3)
            if groups.isEmpty {
                SessionsEmptyCard(title: needle.isEmpty ? S.sessions.empty : S.sessions.noMatches,
                                  hint: needle.isEmpty ? S.sessions.emptyHint : nil)
            } else {
                SessionLegend().padding(.bottom, Space.sp5)
                VStack(alignment: .leading, spacing: Space.sp6) {
                    ForEach(groups) { group in
                        SessionGroupSection(group: group, asksToClose: stagedClose(groups))
                    }
                }
            }
        }
        .modifier(NewSessionDrawer(form: $creating))
        .onAppear(perform: appear)
        .onChange(of: model.router.pendingNewSession) { _, pending in
            if pending && model.router.takeNewSessionRequest() { openNewSession() }
        }
    }

    private func openNewSession() {
        creating = NewSessionForm(devices: DeviceOrder.online(model.connection.devices), preset: nil)
    }

    private func appear() {
        if model.router.takeNewSessionRequest() { openNewSession() }
        applyStage()
    }
}

// MARK: - Preview stages

extension SessionsPage {
    private static let searchPrefix = "sessions.search:"

    /// "sessions.search:<query>" types a query; "sessions.new…" opens the drawer.
    private func applyStage() {
        guard let stage else { return }
        if stage.hasPrefix(Self.searchPrefix) { query = String(stage.dropFirst(Self.searchPrefix.count)) }
        if stage.hasPrefix("sessions.new") { openNewSession() }
    }

    private var stagedFilter: SessionsToolbar.Filter? {
        switch stage {
        case "sessions.filter.agent": .agent
        case "sessions.filter.device": .device
        default: nil
        }
    }

    /// The first working row, whose close asks before it acts.
    private func stagedClose(_ groups: [DeviceGroup]) -> String? {
        guard stage == "sessions.close" else { return nil }
        for group in groups {
            if let row = group.active.first(where: { SessionListLayout.offersClose($0) && $0.dotTone(online: group.online) == .working }) {
                return row.id
            }
        }
        return nil
    }
}
