import SwiftUI
import RCCore

/// The machines this gateway knows about, and how to add another one.
///
/// Every row offers the same three actions the web offers — Rename, Update and
/// Revoke — from one trailing swipe and from the context menu, so nothing is
/// reachable on one app and not the other (`docs/DESIGN.md` § "Devices").
struct DevicesView: View {
    @Environment(AppModel.self) private var model
    @State private var isAdding = false
    @State private var renaming: Device?
    @State private var newName = ""
    @State private var revoking: Device?
    @State private var updating: Device?
    @State private var error: String?

    var body: some View {
        List {
            ForEach(model.connection.devices) { device in
                // `docs/DESIGN.md` § "A device has a page": the row itself
                // opens the machine; its menu and its swipe still act on it
                // without going anywhere.
                NavigationLink(value: device.deviceID) {
                    DeviceRow(device: device,
                              servedBuild: model.connection.config.servedBuild,
                              servedVersion: model.connection.config.servedVersion,
                              localError: model.deviceUpdateError(device.deviceID))
                }
                    .sessionRowLayout()
                    .accessibilityIdentifier("device.\(device.deviceID)")
                    .contextMenu { actions(for: device) }
                    // One swipe carries all three. SwiftUI lays a trailing
                    // swipe out from the edge inwards, so the first listed is
                    // the one nearest the edge and the row reads
                    // Rename · Update · Revoke from left to right.
                    .swipeActions(edge: .trailing) {
                        revokeAction(for: device)
                        updateAction(for: device)
                        renameAction(for: device)
                    }
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
        .navigationDestination(for: String.self) { deviceID in
            DeviceDetailView(deviceID: deviceID).environment(model)
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
        .alert("Update device", isPresented: Binding(get: { updating != nil },
                                                     set: { if !$0 { updating = nil } })) {
            Button("Cancel", role: .cancel) { updating = nil }
            Button("Update") { update() }
        } message: {
            Text(DeviceUpdateText.confirmation(name: updating?.name ?? L10n.string("This device"),
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

    /// The context menu: the same three, in the order the web menu uses.
    @ViewBuilder
    private func actions(for device: Device) -> some View {
        renameAction(for: device)
        updateAction(for: device)
        revokeAction(for: device)
    }

    private func renameAction(for device: Device) -> some View {
        Button { renaming = device; newName = device.name } label: { Label("Rename", systemImage: "pencil") }
            .tint(Theme.inkSecondary)
            .accessibilityIdentifier("device.rename")
    }

    /// The tint is explicit: the app sets its own `.tint` at the root, and a
    /// destructive swipe button takes that over the system red without it.
    private func revokeAction(for device: Device) -> some View {
        Button(role: .destructive) { revoking = device } label: {
            Label("Revoke", systemImage: "trash")
        }
        .tint(Theme.danger)
        .accessibilityIdentifier("device.revoke")
    }

    /// Amendment A22. The action stays on the row whatever state the device is
    /// in and says why it cannot act, rather than disappearing and leaving the
    /// swipe with nothing under it.
    private func updateAction(for device: Device) -> some View {
        let blocked = DeviceUpdate.block(for: device, servedBuild: model.connection.config.servedBuild)
        return Button { updating = device } label: { Label("Update", systemImage: "arrow.down.circle") }
            .tint(Theme.accent)
            .disabled(blocked != nil)
            .accessibilityHint(blocked.map(Self.reason) ?? "")
            .accessibilityIdentifier("device.update")
    }

    private static func reason(_ block: DeviceUpdate.Block) -> String {
        switch block {
        case .offline: L10n.string("This device is offline.")
        case .inFlight: L10n.string("This device is already updating.")
        case .noServedBuild: L10n.string("This gateway is not serving a client build.")
        case .current: L10n.string("This device runs the build the gateway serves.")
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
    private func update() {
        guard let device = updating else { return }
        updating = nil
        Task { await model.updateDevice(device) }
    }
}

/// One machine, behind one glyph. `docs/DESIGN.md` § "The device row": the name
/// and one number on the first line, the dot with its state and platform on the
/// second, the agents as their logos, then the client line. No status column,
/// no rules, and nothing that repeats the name.
struct DeviceRow: View {
    let device: Device
    var servedBuild: String?
    var servedVersion: String?
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
                DeviceRowClientLine(device: device, servedBuild: servedBuild, localError: localError)
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
