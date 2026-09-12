import SwiftUI
import RCCore

/// The machines this gateway knows about, and how to add another one.
///
/// Every row offers the same three actions the web offers — Rename, Update and
/// Remove — from a swipe and from the context menu, so nothing is reachable on
/// one app and not the other (`docs/DESIGN.md` § "Devices").
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
                DeviceRow(device: device,
                          servedBuild: model.connection.config.servedBuild,
                          localError: model.deviceUpdateError(device.deviceID))
                    .sessionRowLayout()
                    .accessibilityIdentifier("device.\(device.deviceID)")
                    .contextMenu { actions(for: device) }
                    .swipeActions(edge: .leading) { updateAction(for: device) }
                    .swipeActions(edge: .trailing) {
                        removeAction(for: device)
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
            Text(L10n.string(
                "Update %@ to the gateway's client? Its service restarts; sessions it drives are stopped.",
                updating?.name ?? L10n.string("This device")))
        }
        .alert("Remove this device?", isPresented: Binding(get: { revoking != nil },
                                                           set: { if !$0 { revoking = nil } })) {
            Button("Cancel", role: .cancel) { revoking = nil }
            Button("Remove", role: .destructive) { revoke() }
        } message: {
            Text(L10n.string(
                "%@ loses its access token and its sessions disappear from this gateway. Agent transcripts on the machine are untouched.",
                revoking?.name ?? L10n.string("This device")))
        }
    }

    /// The context menu: the same three, in the order the web menu uses.
    @ViewBuilder
    private func actions(for device: Device) -> some View {
        renameAction(for: device)
        updateAction(for: device)
        removeAction(for: device)
    }

    private func renameAction(for device: Device) -> some View {
        Button { renaming = device; newName = device.name } label: { Label("Rename", systemImage: "pencil") }
            .tint(Theme.accent)
            .accessibilityIdentifier("device.rename")
    }

    private func removeAction(for device: Device) -> some View {
        Button(role: .destructive) { revoking = device } label: {
            Label("Remove", systemImage: "trash")
        }
        .accessibilityIdentifier("device.remove")
    }

    /// Amendment A22. The action stays on the row whatever state the device is
    /// in and says why it cannot act, rather than disappearing and leaving the
    /// swipe with nothing under it.
    private func updateAction(for device: Device) -> some View {
        let blocked = DeviceUpdate.block(for: device, servedBuild: model.connection.config.servedBuild)
        return Button { updating = device } label: { Label("Update", systemImage: "arrow.down.circle") }
            .tint(Theme.resting)
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
        Task {
            do { _ = try await api.renameDevice(device.deviceID, name: name) }
            catch { self.error = model.connection.message(for: error) }
        }
    }

    private func revoke() {
        guard let device = revoking, let api = model.connection.api else { return }
        revoking = nil
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

/// The same shape as a session row: name and one number on the first line, a
/// dot, a word and the machine on the second. No status column, no rules.
struct DeviceRow: View {
    let device: Device
    var servedBuild: String?
    var localError: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.small) {
                Text(device.name).font(Theme.Text.title).foregroundStyle(Theme.ink).lineLimit(1)
                Spacer(minLength: 0)
                Text(trailing)
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.inkSecondary)
            }
            HStack(spacing: 5) {
                StatusDot(tone: tone)
                Text(device.online ? "online" : "offline")
                    .font(Theme.Text.meta)
                    .foregroundStyle(Theme.inkSecondary)
                Text("·").font(Theme.Text.caption).foregroundStyle(Theme.inkSecondary)
                CodeText("\(device.hostname) · \(device.platform.rawValue) \(device.arch)",
                         font: Theme.Text.metaMono)
                Spacer(minLength: 0)
            }
            if !device.availableAgents.isEmpty {
                Text(device.availableAgents.map(\.displayName).joined(separator: " · "))
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.inkSecondary)
            }
            clientLine
        }
        .accessibilityElement(children: .combine)
    }

    /// Amendment A22: the build this machine runs, and the one line that
    /// replaces it whenever there is something to say about an update.
    @ViewBuilder
    private var clientLine: some View {
        HStack(spacing: 5) {
            CodeText(clientText, font: Theme.Text.metaMono)
            if let notice {
                Text("·").font(Theme.Text.caption).foregroundStyle(Theme.inkSecondary)
                Text(Self.text(of: notice))
                    .font(Theme.Text.caption)
                    .foregroundStyle(notice.isFailure ? Theme.danger : Theme.inkSecondary)
                    .accessibilityIdentifier("device.updateNotice")
            }
            Spacer(minLength: 0)
        }
    }

    private var notice: DeviceUpdate.Notice? {
        DeviceUpdate.notice(for: device, servedBuild: servedBuild, localError: localError)
    }

    /// The build is worth showing only while nothing louder replaces it.
    private var clientText: String {
        guard notice == nil, let build = device.clientBuild else {
            return L10n.string("client %@", device.clientVersion)
        }
        return L10n.string("client %@ · %@", device.clientVersion, DeviceUpdate.shortBuild(build))
    }

    private var tone: DotTone {
        if device.updateState == .updating { return .working }
        return device.online ? .live : .off
    }

    private static func text(of notice: DeviceUpdate.Notice) -> String {
        switch notice {
        case .available: L10n.string("Update available")
        case .updating: L10n.string("Updating…")
        case .failed(let message): L10n.string("Update failed · %@", message)
        }
    }

    /// Latency while it answers, and how long ago it last did when it does not.
    private var trailing: String {
        if device.online { return device.latencyMS.map { "\($0) ms" } ?? "" }
        return RelativeTime.short(since: device.lastSeen)
    }
}

extension DeviceUpdate.Notice {
    var isFailure: Bool {
        if case .failed = self { return true }
        return false
    }
}

#Preview("Devices") {
    DemoPreview { NavigationStack { DevicesView() } }
}
