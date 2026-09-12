import SwiftUI
import Combine
import RCCore
#if os(iOS)
import UIKit
#endif

/// The pairing sheet: pick a platform, copy one command, watch it arrive.
struct AddDeviceSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var flow: PairingFlow?
    @State private var copied = false
    @State private var showsManual = false
    @State private var showsScanner = false
    @State private var now = Date()

    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.large) {
                    if let flow, let pairing = flow.pairing {
                        code(flow, pairing: pairing)
                    } else {
                        waiting
                    }
                    if let error = flow?.errorMessage {
                        Text(error).font(.footnote).foregroundStyle(Theme.danger)
                    }
                }
                .padding(.horizontal, Theme.Space.page)
                .padding(.bottom, Theme.Space.large)
            }
            .pageBackground()
            .navigationTitle("Add device")
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        Task { await flow?.cancel(); dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .disabled(flow?.isComplete != true)
                        .accessibilityIdentifier("pairing.done")
                }
            }
            .task { await begin() }
            // The scanner covers this sheet full screen, which takes it off the
            // window and back on again. Listening is re-established on the way
            // back rather than left behind with the cover.
            .onAppear { listen() }
            .onDisappear { model.connection.removeFrameHandler("pairing") }
            .onReceive(tick) { now = $0 }
            .sheet(isPresented: $showsManual) {
                ManualInstallView(command: flow?.command ?? "", code: flow?.code ?? "")
            }
            #if os(iOS)
            .fullScreenCover(isPresented: $showsScanner) {
                if let flow {
                    ScanPairingView(flow: flow, origins: origins, installCommand: scanCommand,
                                    scanner: model.codeScanner)
                }
            }
            #endif
        }
        .sheetSize()
    }

    /// One code, reached one of two ways. A code minted here comes with the
    /// one-liner that uses it; a code claimed from a scan does not, because the
    /// host that printed the QR code has already run one (A23).
    @ViewBuilder
    private func code(_ flow: PairingFlow, pairing: PairingFlow.Pairing) -> some View {
        if pairing.install != nil {
            Text("Run one command on the machine where your agents live. It dials out to the gateway, so nothing is exposed on the host.")
                .font(.subheadline)
                .foregroundStyle(Theme.inkSecondary)
                .fixedSize(horizontal: false, vertical: true)
            platformPicker(flow)
            commandCard(flow)
            #if os(iOS)
            scanButton
            #endif
            progressCard(flow)
            Button("Manual install") { showsManual = true }
                .font(.footnote)
                .buttonStyle(.plain)
                .foregroundStyle(Theme.ink)
                .frame(minHeight: Theme.Touch.minimum)
        } else {
            claimedCard(flow)
            progressCard(flow)
        }
    }

    private var waiting: some View {
        HStack { ProgressView(); Text("Requesting a code").foregroundStyle(Theme.inkSecondary) }
    }

    #if os(iOS)
    /// Amendment A23: the other way in. The host runs one command, prints a QR
    /// code, and this claims it — no code is typed anywhere.
    private var scanButton: some View {
        Button {
            showsScanner = true
        } label: {
            Label("Scan a code", systemImage: "qrcode.viewfinder")
        }
        .buttonStyle(ChipButtonStyle())
        .accessibilityIdentifier("pairing.scan")
    }
    #endif

    /// The code the gateway minted for a scanned host. There is no one-liner
    /// beside it: the host ran one to get here.
    private func claimedCard(_ flow: PairingFlow) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            Text("This host asked to join your gateway.")
                .font(.subheadline)
                .foregroundStyle(Theme.inkSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Text(flow.code).font(Theme.mono).foregroundStyle(Theme.ink)
                Text("single use").font(.caption).foregroundStyle(Theme.inkSecondary)
                Spacer()
                Text(flow.hasExpired(now: now) ? L10n.string("expired")
                                               : L10n.string("expires in %@", flow.expiry(now: now)))
                    .font(.caption)
                    .foregroundStyle(flow.hasExpired(now: now) ? Theme.danger : Theme.inkSecondary)
                    .accessibilityIdentifier("pairing.claimedCode")
            }
        }
        .card()
    }

    /// The origins a scanned link may name: the one this app dials, and the one
    /// the gateway publishes to the world. They differ on a LAN sign-in.
    private var origins: [GatewayEndpoint] {
        var found = model.connection.endpoint.map { [$0] } ?? []
        let published = model.connection.config.publicOrigin
        if !published.isEmpty, let endpoint = try? GatewayEndpoint(published),
           !found.contains(endpoint) {
            found.append(endpoint)
        }
        return found
    }

    private var scanCommand: String {
        let origin = model.connection.config.publicOrigin.isEmpty
            ? (model.connection.endpoint?.origin ?? "")
            : model.connection.config.publicOrigin
        return "curl -fsSL \(origin)/install.sh | sh"
    }

    @ViewBuilder
    private func platformPicker(_ flow: PairingFlow) -> some View {
        @Bindable var flow = flow
        Picker("Platform", selection: $flow.platform) {
            Text("macOS").tag(DevicePlatform.macos)
            Text("Linux").tag(DevicePlatform.linux)
        }
        .pickerStyle(.segmented)
        .accessibilityIdentifier("pairing.platform")
    }

    private func commandCard(_ flow: PairingFlow) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            HStack(alignment: .top, spacing: Theme.Space.small) {
                Text(flow.command)
                    .font(Theme.mono)
                    .foregroundStyle(Theme.ink)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("pairing.command")
                Spacer(minLength: 0)
                Button {
                    copy(flow.command)
                } label: {
                    Text(L10n.string(copied ? "Copied" : "Copy"))
                        .frame(minWidth: 54)
                }
                .buttonStyle(ChipButtonStyle())
                .accessibilityIdentifier("pairing.copy")
            }
            Divider().overlay(Theme.border)
            HStack {
                Text(flow.code).font(Theme.mono).foregroundStyle(Theme.ink)
                Text("single use").font(.caption).foregroundStyle(Theme.inkSecondary)
                Spacer()
                Text(flow.hasExpired(now: now) ? L10n.string("expired")
                                               : L10n.string("expires in %@", flow.expiry(now: now)))
                    .font(.caption)
                    .foregroundStyle(flow.hasExpired(now: now) ? Theme.danger : Theme.inkSecondary)
                    .accessibilityIdentifier("pairing.expiry")
            }
            if flow.hasExpired(now: now) {
                Button("Get a new code") { Task { await flow.begin() } }
                    .buttonStyle(ChipButtonStyle())
            }
        }
        .card()
    }

    private func progressCard(_ flow: PairingFlow) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            ForEach(flow.steps) { step in
                HStack(spacing: Theme.Space.small) {
                    Image(systemName: step.done ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(step.done ? Theme.ink : Theme.resting)
                    Text(step.title)
                        .font(.subheadline)
                        .foregroundStyle(step.done ? Theme.ink : Theme.inkSecondary)
                    if step.id == .agents, !flow.detectedAgents.isEmpty {
                        Text(flow.detectedAgents).font(Theme.mono).foregroundStyle(Theme.inkSecondary)
                    }
                    Spacer()
                }
                .frame(minHeight: 32)
                .accessibilityElement(children: .combine)
            }
        }
        .card()
        .accessibilityIdentifier("pairing.steps")
    }

    private func begin() async {
        guard flow == nil, let created = model.pairingFlow() else { return }
        flow = created
        listen()
        await created.begin()
    }

    private func listen() {
        guard let flow else { return }
        model.connection.addFrameHandler("pairing") { [weak flow] frame in flow?.receive(frame) }
    }

    private func copy(_ text: String) {
        #if os(iOS)
        UIPasteboard.general.string = text
        #endif
        copied = true
        Task { try? await Task.sleep(for: .seconds(2)); copied = false }
    }
}

/// The same steps, spelled out for a host without curl.
private struct ManualInstallView: View {
    let command: String
    let code: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.medium) {
                    Text("Download the installer, then run it with your pairing code.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSecondary)
                    Text(command.replacingOccurrences(of: " | sh -s --", with: " -o install.sh\nsh install.sh"))
                        .font(Theme.mono)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                        .card()
                    Text(L10n.string("The code %@ can be used once and expires ten minutes after it was issued.",
                                 code))
                        .font(.footnote)
                        .foregroundStyle(Theme.inkSecondary)
                }
                .padding(Theme.Space.page)
            }
            .pageBackground()
            .navigationTitle("Manual install")
            .inlineNavigationTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .sheetSize()
    }
}

#Preview("Add device") {
    DemoPreview { AddDeviceSheet() }
}
