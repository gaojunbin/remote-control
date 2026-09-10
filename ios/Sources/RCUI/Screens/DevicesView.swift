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
                    .listRowBackground(Theme.surface)
                    .accessibilityIdentifier("device.\(device.deviceID)")
                    .contextMenu {
                        Button("Rename") { renaming = device; newName = device.name }
                        Button("Remove device", role: .destructive) { revoking = device }
                    }
            }

            if model.connection.devices.isEmpty {
                EmptyStateView(symbol: "desktopcomputer",
                               title: "No devices yet",
                               message: "Run one command on the machine where your agents live. It dials out to the gateway; nothing is exposed on the host.")
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
            Text("\(revoking?.name ?? "This device") loses its access token and its sessions disappear from this gateway. Agent transcripts on the machine are untouched.")
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

struct DeviceRow: View {
    let device: Device

    var body: some View {
        HStack(alignment: .top, spacing: Theme.Space.small) {
            Circle()
                .fill(device.online ? Theme.running : Theme.resting)
                .frame(width: 8, height: 8)
                .padding(.top, 6)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(device.name).font(.body.weight(.medium)).foregroundStyle(Theme.ink)
                CodeText("\(device.hostname) · \(device.platform.rawValue) \(device.arch)")
                if !device.availableAgents.isEmpty {
                    Text(device.availableAgents.map(\.displayName).joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(Theme.inkSecondary)
                }
            }
            Spacer(minLength: Theme.Space.small)
            VStack(alignment: .trailing, spacing: 3) {
                Text(device.online ? "online" : "offline")
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSecondary)
                if let latency = device.latencyMS, device.online {
                    Text("\(latency) ms").font(.caption).foregroundStyle(Theme.inkSecondary)
                } else if !device.online {
                    Text(RelativeTime.short(since: device.lastSeen))
                        .font(.caption).foregroundStyle(Theme.inkSecondary)
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

#Preview("Devices") {
    DemoPreview { NavigationStack { DevicesView() } }
}
