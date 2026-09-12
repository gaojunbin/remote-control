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
    @State private var now = Date()

    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.large) {
                    Text("Run one command on the machine where your agents live. It dials out to the gateway, so nothing is exposed on the host.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)

                    if let flow {
                        platformPicker(flow)
                        commandCard(flow)
                        progressCard(flow)
                        Button("Manual install") { showsManual = true }
                            .font(.footnote)
                            .buttonStyle(.plain)
                            .foregroundStyle(Theme.ink)
                            .frame(minHeight: Theme.Touch.minimum)
                        if let error = flow.errorMessage {
                            Text(error).font(.footnote).foregroundStyle(Theme.danger)
                        }
                    } else {
                        HStack { ProgressView(); Text("Requesting a code").foregroundStyle(Theme.inkSecondary) }
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
            .onDisappear { model.connection.removeFrameHandler("pairing") }
            .onReceive(tick) { now = $0 }
            .sheet(isPresented: $showsManual) {
                ManualInstallView(command: flow?.command ?? "", code: flow?.code ?? "")
            }
        }
        .sheetSize()
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
        model.connection.addFrameHandler("pairing") { [weak created] frame in created?.receive(frame) }
        await created.begin()
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
