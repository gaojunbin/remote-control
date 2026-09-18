import SwiftUI
import RCCore

/// The machines this gateway knows about, and how to add another one.
///
/// Amendment A38, rule 20: the row's own tap opens a shell on the machine, and
/// its swipe and its menu hold the rest — Rename · Retry update (only while one
/// has failed) · Show quota · Revoke — in the order the web uses, so nothing is
/// reachable on one app and not the other (`docs/DESIGN.md` § "Devices" and
/// § "A device has a page, and a device row opens a terminal").
struct DevicesView: View {
    @Environment(AppModel.self) private var model
    @State private var isAdding = false
    @State private var renaming: Device?
    @State private var newName = ""
    @State private var revoking: Device?
    @State private var retrying: Device?
    @State private var error: String?
    /// The platform the list is narrowed to; a view of the list, not a setting.
    @State private var platformFilter: DevicePlatform?
    /// Why the last tap opened nothing, and on which row. A machine that is
    /// offline or offers no terminal says so where it stands rather than
    /// pushing a screen that would only say it again.
    @State private var refusal: (deviceID: String, reason: String)?

    private var shown: [Device] {
        DeviceFilter.apply(model.connection.devices, platform: platformFilter)
    }

    var body: some View {
        List {
            ForEach(shown) { device in
                // Rule 20: the row itself opens a shell on the machine. It is a
                // button and not a link because the tap does not always lead
                // anywhere — an offline machine, or one that offers no
                // terminal, answers in place.
                Button { open(device) } label: {
                    DeviceRow(device: device, localError: model.deviceUpdateError(device.deviceID))
                        // The row is the target, not the words in it: a plain
                        // button hit-tests what it draws, and a row with an
                        // empty band between its lines would swallow the tap
                        // that landed there.
                        .contentShape(Rectangle())
                }
                    .buttonStyle(.plain)
                    .sessionRowLayout()
                    .accessibilityIdentifier("device.\(device.deviceID)")
                    .contextMenu { actions(for: device) }
                    // One swipe carries them all. SwiftUI lays a trailing swipe
                    // out from the edge inwards, so the first listed is the one
                    // nearest the edge and the row reads Rename · Retry update
                    // · Show quota · Revoke from left to right.
                    .swipeActions(edge: .trailing) {
                        ForEach(DeviceRowAction.menu(for: device).reversed()) { action in
                            button(action, for: device)
                        }
                    }

                // The answer to a tap that opened nothing, under the row it
                // belongs to: a line of its own, so the row's own reading is
                // still the machine and its state.
                if refusal?.deviceID == device.deviceID, let reason = refusal?.reason {
                    Text(reason)
                        .font(Theme.Text.caption)
                        .foregroundStyle(Theme.inkSecondary)
                        .listRowBackground(Theme.surface)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 0, leading: Theme.Space.medium,
                                                  bottom: 14, trailing: Theme.Space.medium))
                        .accessibilityIdentifier("device.terminalRefusal")
                }
            }

            if let platformFilter, shown.isEmpty, !model.connection.devices.isEmpty {
                Text(L10n.string("No %@ devices", DeviceLine.platformName(platformFilter)))
                    .font(Theme.Text.meta)
                    .foregroundStyle(Theme.inkSecondary)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .accessibilityIdentifier("devices.platformFilter.empty")
            }

            if model.connection.devices.isEmpty {
                EmptyStateView(symbol: "desktopcomputer",
                               title: L10n.string("No devices yet"),
                               message: L10n.string(
                                "Run one command on the machine where your agents live. It dials out to the gateway; nothing is exposed on the host."))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }

            if let error {
                Text(error).font(.footnote).foregroundStyle(Theme.danger)
                    .listRowBackground(Color.clear)
            }
        }
        .groupedList()
        .scrollContentBackground(.hidden)
        .pageBackground()
        .navigationTitle("Devices")
        .toolbar {
            ToolbarItem(placement: .trailingBar) { platformFilterMenu }
        }
        .navigationDestination(for: DeviceRoute.self) { route in
            switch route {
            case .page(let deviceID):
                DeviceDetailView(deviceID: deviceID).environment(model)
            case .terminal(let deviceID):
                TerminalScreen(deviceID: deviceID).environment(model)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            Button {
                isAdding = true
            } label: {
                Label("Add device", systemImage: "plus")
            }
            .buttonStyle(PrimaryButtonStyle())
            .padding(.horizontal, Theme.Space.page)
            .padding(.vertical, Theme.Space.small)
            .barBackground()
            .disabled(model.isDemo && model.connection.api == nil)
            .accessibilityIdentifier("devices.add")
        }
        .sheet(isPresented: $isAdding) {
            AddDeviceSheet().environment(model)
        }
        .alert("Rename device", isPresented: Binding(get: { renaming != nil },
                                                     set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $newName)
            Button("Cancel", role: .cancel) { renaming = nil }
            Button("Save") { rename() }
        }
        .alert("Update device", isPresented: Binding(get: { retrying != nil },
                                                     set: { if !$0 { retrying = nil } })) {
            Button("Cancel", role: .cancel) { retrying = nil }
            Button("Update") { retry() }
        } message: {
            Text(DeviceUpdateText.confirmation(name: retrying?.name ?? L10n.string("This device"),
                                               servedVersion: model.connection.config.servedVersion))
        }
        .alert("Revoke device", isPresented: Binding(get: { revoking != nil },
                                                     set: { if !$0 { revoking = nil } })) {
            Button("Cancel", role: .cancel) { revoking = nil }
            Button("Revoke device", role: .destructive) { revoke() }
        } message: {
            Text(L10n.string(
                "Revoke %@? Its token stops working and its sessions leave this gateway. The machine keeps its agents and transcripts.",
                revoking?.name ?? L10n.string("This device")))
        }
    }

    /// All, then the platforms the list actually contains — the same control
    /// the Sessions screen has for agents (`docs/DESIGN.md` § "Devices can be
    /// filtered by platform"). The chosen platform's word stands beside the
    /// glyph, so the narrowed list says what it is narrowed to.
    @ViewBuilder
    private var platformFilterMenu: some View {
        let options = DeviceFilter.platforms(in: model.connection.devices)
        if !options.isEmpty {
            Menu {
                platformChoice(nil, label: L10n.string("All devices"))
                ForEach(options, id: \.rawValue) { platform in
                    platformChoice(platform, label: DeviceLine.platformName(platform))
                }
            } label: {
                HStack(spacing: Theme.Space.hair + 2) {
                    Image(systemName: "line.3.horizontal.decrease")
                    if let platformFilter {
                        Text(DeviceLine.platformName(platformFilter)).font(Theme.Text.meta)
                    }
                }
                .foregroundStyle(platformFilter == nil ? Theme.inkSecondary : Theme.ink)
                .frame(minHeight: Theme.Touch.minimum)
            }
            .accessibilityLabel("Filter by platform")
            .accessibilityValue(platformFilter.map(DeviceLine.platformName) ?? L10n.string("All devices"))
            .accessibilityIdentifier("devices.platformFilter")
        }
    }

    private func platformChoice(_ platform: DevicePlatform?, label: String) -> some View {
        Button {
            platformFilter = platform
        } label: {
            if platformFilter == platform {
                Label(label, systemImage: "checkmark")
            } else {
                Text(label)
            }
        }
        .accessibilityIdentifier("devices.platformFilter.\(platform?.rawValue ?? "all")")
    }

    /// Rule 20: the row's tap opens a shell where one can be opened, and says
    /// why where it cannot. The reason stands beside the row for a moment
    /// rather than in an alert: nothing went wrong, and nothing needs dismissing.
    private func open(_ device: Device) {
        switch DeviceTap.outcome(for: device) {
        case .terminal:
            refusal = nil
            model.devicePath.append(.terminal(device.deviceID))
        case .refused(let reason):
            refusal = (device.deviceID, reason)
        }
    }

    /// The context menu: the same actions the swipe holds, in the order rule 20
    /// gives them.
    @ViewBuilder
    private func actions(for device: Device) -> some View {
        ForEach(DeviceRowAction.menu(for: device)) { action in
            button(action, for: device)
        }
    }

    /// One action, wherever it is drawn. The swipe and the menu carry the same
    /// buttons, so there is one place that decides what each one says and does.
    @ViewBuilder
    private func button(_ action: DeviceRowAction, for device: Device) -> some View {
        switch action {
        case .rename:
            Button { renaming = device; newName = device.name } label: {
                Label("Rename", systemImage: action.symbol)
            }
            .tint(Theme.inkSecondary)
            .accessibilityIdentifier(action.identifier)
        case .retryUpdate:
            // Amendment A36. The gateway keeps every device on the wheel it
            // serves, so there is nothing to offer until one of those updates
            // fails: only then is the action on the row, and it says why it
            // cannot act rather than disappearing again.
            let blocked = DeviceUpdate.block(for: device,
                                             servedBuild: model.connection.config.servedBuild)
            Button { retrying = device } label: {
                Label("Retry update", systemImage: action.symbol)
            }
            .tint(Theme.accent)
            .disabled(blocked != nil)
            .accessibilityHint(blocked.map(DeviceUpdateText.reason) ?? "")
            .accessibilityIdentifier(action.identifier)
        case .showQuota:
            // Amendment A33's page, which the row's tap used to open.
            Button { model.devicePath.append(.page(device.deviceID)) } label: {
                Label("Show quota", systemImage: action.symbol)
            }
            .tint(Theme.inkSecondary)
            .accessibilityIdentifier(action.identifier)
        case .revoke:
            // The tint is explicit: the app sets its own `.tint` at the root,
            // and a destructive swipe button takes that over the system red.
            Button(role: .destructive) { revoking = device } label: {
                Label("Revoke", systemImage: action.symbol)
            }
            .tint(Theme.danger)
            .accessibilityIdentifier(action.identifier)
        }
    }

    // Each of these reads the row it acts on while the tap is still being
    // handled: dismissing the alert clears the state, and it can do so before
    // a task started here gets to run.

    private func rename() {
        guard let device = renaming, let api = model.connection.api else { return }
        let name = newName.trimmed
        renaming = nil
        // The line belongs to the attempt being made, not to the screen: one
        // failure must not outlive it and sit under a later success.
        error = nil
        Task {
            do { _ = try await api.renameDevice(device.deviceID, name: name) }
            catch { self.error = model.connection.message(for: error) }
        }
    }

    private func revoke() {
        guard let device = revoking, let api = model.connection.api else { return }
        revoking = nil
        error = nil
        Task {
            do { try await api.revokeDevice(device.deviceID) }
            catch { self.error = model.connection.message(for: error) }
        }
    }

    /// A refused update is the row's own news, not the gateway's: nothing
    /// started, so no `device.updated` will ever carry it.
    private func retry() {
        guard let device = retrying else { return }
        retrying = nil
        Task { await model.updateDevice(device) }
    }
}

/// One machine, behind one glyph. `docs/DESIGN.md` § "The device row": the name
/// and one number on the first line, the dot with its state and platform on the
/// second, the agents as their logos. No status column, no rules, and nothing
/// that repeats the name. A fourth line is drawn only where an update is
/// running or has failed — the version the machine runs is never on the row
/// (A36).
struct DeviceRow: View {
    let device: Device
    var localError: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.small) {
            // The same glyph for every machine whatever its platform: a minimal
            // outline laptop. The app cannot tell a laptop from a desktop, and
            // one honest mark beats a wrong guess. It is what keeps two rows
            // apart now that no separator is drawn between them.
            LaptopGlyph()
            VStack(alignment: .leading, spacing: 5) {
                HStack(alignment: .firstTextBaseline, spacing: Theme.Space.small) {
                    Text(device.name).font(Theme.Text.title).foregroundStyle(Theme.ink).lineLimit(1)
                    Spacer(minLength: 0)
                    Text(trailing)
                        .font(Theme.Text.caption)
                        .foregroundStyle(Theme.inkSecondary)
                }
                DeviceStatusLine(device: device)
                if !device.availableAgents.isEmpty {
                    DeviceAgentsLine(agents: device.availableAgents)
                }
                if let notice = DeviceUpdate.notice(for: device, localError: localError) {
                    DeviceUpdateLine(notice: notice)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    /// Latency while it answers, and how long ago it last did when it does not.
    private var trailing: String {
        if device.online { return device.latencyMS.map { "\($0) ms" } ?? "" }
        return RelativeTime.short(since: device.lastSeen)
    }
}

/// The agents a machine reported, as their logos and nothing else
/// (`docs/DESIGN.md` § "The device row"). A logo is a drawing, so each one
/// carries its agent's name as its accessible label and the row still reads
/// aloud; the versions are on the machine's page, beside the accounts.
private struct DeviceAgentsLine: View {
    let agents: [AgentInfo]

    var body: some View {
        HStack(spacing: Theme.Space.small) {
            ForEach(agents) { info in
                AgentLogo(agent: info.agent, size: Theme.Mark.control)
                    .accessibilityElement()
                    .accessibilityLabel(info.displayName)
            }
            Spacer(minLength: 0)
        }
    }
}

#Preview("Devices") {
    DemoPreview { NavigationStack { DevicesView() } }
}
