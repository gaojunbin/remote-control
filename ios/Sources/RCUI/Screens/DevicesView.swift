import SwiftUI
import RCCore

/// The machines this gateway knows about, and how to add another one.
struct DevicesView: View {
    @Environment(AppModel.self) private var model
    @State private var isAdding = false
    @State private var renaming: Device?
    @State private var newName = ""
    @State private var revoking: Device?
    @State private var error: String?

    var body: some View {
        List {
            ForEach(model.connection.devices) { device in
                DeviceRow(device: device)
                    .sessionRowLayout()
                    .accessibilityIdentifier("device.\(device.deviceID)")
                    .contextMenu {
                        Button("Rename") { renaming = device; newName = device.name }
                        Button("Remove device", role: .destructive) { revoking = device }
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
            Button("Save") { Task { await rename() } }
        }
        .alert("Remove this device?", isPresented: Binding(get: { revoking != nil },
                                                           set: { if !$0 { revoking = nil } })) {
            Button("Cancel", role: .cancel) { revoking = nil }
            Button("Remove", role: .destructive) { Task { await revoke() } }
        } message: {
            Text(L10n.string(
                "%@ loses its access token and its sessions disappear from this gateway. Agent transcripts on the machine are untouched.",
                revoking?.name ?? L10n.string("This device")))
        }
    }

    private func rename() async {
        guard let device = renaming, let api = model.connection.api else { return }
        renaming = nil
        do { _ = try await api.renameDevice(device.deviceID, name: newName.trimmed) }
        catch { self.error = model.connection.message(for: error) }
    }

    private func revoke() async {
        guard let device = revoking, let api = model.connection.api else { return }
        revoking = nil
        do { try await api.revokeDevice(device.deviceID) }
        catch { self.error = model.connection.message(for: error) }
    }
}

/// The same shape as a session row: name and one number on the first line, a
/// dot, a word and the machine on the second. No status column, no rules.
struct DeviceRow: View {
    let device: Device

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
                Circle()
                    .fill(device.online ? Theme.running : Theme.resting)
                    .frame(width: 8, height: 8)
                    .accessibilityHidden(true)
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
        }
        .accessibilityElement(children: .combine)
    }

    /// Latency while it answers, and how long ago it last did when it does not.
    private var trailing: String {
        if device.online { return device.latencyMS.map { "\($0) ms" } ?? "" }
        return RelativeTime.short(since: device.lastSeen)
    }
}

#Preview("Devices") {
    DemoPreview { NavigationStack { DevicesView() } }
}
