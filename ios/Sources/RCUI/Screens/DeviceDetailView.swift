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
/// The three actions the row offers — Rename, Update, Revoke — are not repeated
/// here.
struct DeviceDetailView: View {
    let deviceID: String

    @Environment(AppModel.self) private var model
    @State private var fresh: [String: [AgentAccount]] = [:]
    @State private var phase: QuotaPhase = .checking
    @State private var failure: String?

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
    }

    /// The machine as its row words it, minus the name the navigation bar is
    /// already carrying.
    private func header(_ device: Device) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.small) {
                DeviceStatusLine(device: device)
                Text(trailing(device))
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.inkSecondary)
            }
            DeviceClientLine(device: device,
                             servedBuild: model.connection.config.servedBuild,
                             localError: model.deviceUpdateError(device.deviceID))
            if let failure {
                Text(failure)
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.inkSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("device.quota.failure")
            }
        }
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
