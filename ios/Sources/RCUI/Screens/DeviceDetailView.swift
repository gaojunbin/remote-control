import SwiftUI
import RCCore

/// Amendment A33: one machine's page — what it is, which coding agents are on
/// it, how each one is signed in and what is left of its quota.
///
/// `docs/DESIGN.md` § "A device has a page". The credentials are already in the
/// device list, so they are on screen the moment the page opens; the windows
/// are read on request, so the page asks for them itself and says "Checking…"
/// where the meters go until the answer arrives. What that answer brings stays
/// here: the stored device keeps its accounts without windows, so the list
/// behind this page never redraws because a percentage moved.
///
/// The row's Rename and Revoke are not repeated here. Retry update is, because
/// it belongs to the notice it stands beside (`docs/DESIGN.md` § "A device
/// keeps itself current").
struct DeviceDetailView: View {
    let deviceID: String

    @Environment(AppModel.self) private var model
    @State private var fresh: [String: [AgentAccount]] = [:]
    @State private var phase: QuotaPhase = .checking
    @State private var failure: String?
    @State private var isRetrying = false

    private var device: Device? { model.connection.device(deviceID) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.medium) {
                if let device {
                    header(device)
                    agents(of: device)
                } else {
                    Text("That device is no longer on this gateway.")
                        .font(Theme.Text.meta)
                        .foregroundStyle(Theme.inkSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Theme.Space.page)
        }
        .pageBackground()
        .navigationTitle(device?.name ?? "")
        .inlineNavigationTitle()
        .refreshable { await read() }
        .task(id: deviceID) { await read() }
        .accessibilityIdentifier("device.page")
        .alert("Update device", isPresented: $isRetrying) {
            Button("Cancel", role: .cancel) { isRetrying = false }
            Button("Update") { retry() }
        } message: {
            Text(DeviceUpdateText.confirmation(name: device?.name ?? L10n.string("This device"),
                                               servedVersion: model.connection.config.servedVersion))
        }
    }

    /// The machine as its row words it, minus the name the navigation bar is
    /// already carrying, plus the facts the row no longer carries: the hostname
    /// and the architecture are checked here or nowhere (`docs/DESIGN.md`
    /// § "The device row").
    private func header(_ device: Device) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.small) {
                DeviceStatusLine(device: device)
                Text(trailing(device))
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.inkSecondary)
            }
            DeviceFactsLine(device: device)
            updateLine(device)
            if let failure {
                Text(failure)
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.inkSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("device.quota.failure")
            }
        }
    }

    /// The page says no more about the client than the row does (A36): what is
    /// happening to it, and — where the gateway gave up — a way to ask again.
    /// A machine being kept current draws no line here at all.
    @ViewBuilder
    private func updateLine(_ device: Device) -> some View {
        if let notice = DeviceUpdate.notice(for: device,
                                            localError: model.deviceUpdateError(device.deviceID)) {
            HStack(spacing: Theme.Space.small) {
                DeviceUpdateLine(notice: notice)
                if DeviceUpdate.canRetry(device) { retryButton(device) }
                Spacer(minLength: 0)
            }
        }
    }

    private func retryButton(_ device: Device) -> some View {
        let blocked = DeviceUpdate.block(for: device, servedBuild: model.connection.config.servedBuild)
        return Button("Retry update") { isRetrying = true }
            .font(Theme.Text.caption.weight(.medium))
            .buttonStyle(.plain)
            .foregroundStyle(Theme.accent)
            .disabled(blocked != nil)
            .opacity(blocked == nil ? 1 : 0.4)
            .accessibilityHint(blocked.map(DeviceUpdateText.reason) ?? "")
            .accessibilityIdentifier("device.retryUpdate")
    }

    @ViewBuilder
    private func agents(of device: Device) -> some View {
        if device.availableAgents.isEmpty {
            Text("This machine reported no agents.")
                .font(Theme.Text.meta)
                .foregroundStyle(Theme.inkSecondary)
                .accessibilityIdentifier("device.noAgents")
        } else {
            // The device's own order, which is the order its row lists them in.
            ForEach(device.availableAgents) { info in
                AgentAccountCard(info: info, accounts: accounts(for: info), phase: phase)
            }
        }
    }

    /// The fresh credentials where the reply brought them, the stored ones
    /// until then. An agent the device did not look at has none either way.
    private func accounts(for info: AgentInfo) -> [AgentAccount]? {
        fresh[info.agent] ?? info.accounts
    }

    /// Latency while it answers, and how long ago it last did when it does not.
    private func trailing(_ device: Device) -> String {
        if device.online { return device.latencyMS.map { "\($0) ms" } ?? "" }
        return RelativeTime.short(since: device.lastSeen)
    }

    /// A refused retry is the page's own news, not the gateway's: nothing
    /// started, so no `device.updated` will ever carry it.
    private func retry() {
        guard let device else { return }
        isRetrying = false
        Task { await model.updateDevice(device) }
    }

    /// Ask the device what its agents are signed in with and what they have
    /// spent. An offline machine is not asked at all: the accounts on screen
    /// are its last word, and the meters say so rather than failing.
    private func read() async {
        guard let device, let channel = model.connection.channel else { return }
        failure = nil
        guard device.online else {
            phase = .offline
            return
        }
        phase = fresh.isEmpty ? .checking : .ready
        do {
            let result = try await channel.request(.agents(deviceID: deviceID), as: AgentsResult.self)
            // An agent the reply says nothing about keeps what the list stored,
            // so "the device did not look" stays what it was rather than
            // becoming "signed in nowhere".
            var merged: [String: [AgentAccount]] = [:]
            for agent in result.agents {
                if let accounts = agent.accounts { merged[agent.agent] = accounts }
            }
            fresh = merged
            phase = .ready
        } catch let error as GatewayErrorBody where error.code == .deviceOffline {
            phase = .offline
        } catch {
            phase = .unavailable
            failure = model.connection.message(for: error)
        }
    }
}

#Preview("Device") {
    DemoPreview {
        NavigationStack { DeviceDetailView(deviceID: DemoFixtures.macDeviceID) }
    }
}
