import RCCore
import SwiftUI

/// The drawer's sections, 20 points apart: the device with its latency, the
/// agent as a segmented control of logos, the three lists the agent offers and
/// its speed, the working directory with the recent ones, and git.
struct NewSessionFields: View {
    let form: NewSessionForm
    @Environment(MacAppModel.self) private var model
    @Environment(\.previewStage) private var stage
    @State private var browser: DirectoryBrowser?

    var body: some View {
        let devices = DeviceOrder.online(model.connection.devices)
        let device = form.device(in: devices)
        let agent = form.agent(of: device)
        VStack(alignment: .leading, spacing: Space.sp5) {
            DeviceField(form: form, devices: devices, device: device, open: stage == Stage.device)
            AgentField(form: form, device: device, agent: agent)
            AgentOptionFields(form: form, agent: agent)
            DirectoryField(form: form, device: device, browse: browse)
            GitField(form: form, agent: agent)
        }
        .task(id: form.deviceID) { await form.loadHome(channel: model.connection.channel) }
        .task(id: DirectoryProbe.key(deviceID: form.deviceID, path: form.cwd)) {
            await form.probe.run(deviceID: form.deviceID, path: form.cwd, channel: model.connection.channel)
        }
        .modifier(DirectoryPicker(browser: $browser) { form.setPath($0) })
        .task(id: stage) { await openStaged() }
    }

    /// Browse opens the picker on the path in the field, or on the home.
    private func browse() {
        guard let deviceID = form.deviceID else { return }
        let browser = DirectoryBrowser(deviceID: deviceID, channel: model.connection.channel)
        let start = form.cwd.trimmingCharacters(in: .whitespacesAndNewlines)
        self.browser = browser
        Task { await browser.open(start.isEmpty ? nil : start) }
    }

    // MARK: - Preview stages

    enum Stage {
        static let device = "sessions.new.device"
        static let browse = "sessions.new.browse"
        static let folder = "sessions.new.folder"
        static let clash = "sessions.new.folder.clash"
    }

    /// The picker, its new folder row, and a name the device already has, for
    /// a render — each once the drawer's first listing is in. The clash is made
    /// on the device with the most agents, one level up from where the picker
    /// opens, where a sibling's name is taken.
    private func openStaged() async {
        guard let stage, [Stage.browse, Stage.folder, Stage.clash].contains(stage) else { return }
        if stage == Stage.clash,
           let busiest = DeviceOrder.online(model.connection.devices).max(by: { $0.agents.count < $1.agents.count }) {
            form.chooseDevice(busiest.deviceID)
        }
        await waitFor { !form.cwd.isEmpty }
        browse()
        guard stage != Stage.browse, let browser else { return }
        await waitFor { browser.listing != nil }
        if stage == Stage.clash, browser.listing?.entries.isEmpty == true, let parent = browser.listing?.parent {
            await browser.open(parent)
        }
        browser.startNaming()
        guard stage == Stage.clash, let taken = browser.listing?.entries.first?.name else { return }
        browser.folderName = taken
        await browser.createFolder()
    }

    private func waitFor(_ condition: () -> Bool) async {
        for _ in 0..<40 where !condition() { try? await Task.sleep(for: .milliseconds(50)) }
    }
}

/// The device, with its online dot, its name and its latency, from the online
/// devices in name order.
private struct DeviceField: View {
    let form: NewSessionForm
    let devices: [Device]
    let device: Device?
    let open: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            FieldLabel(S.newSession.device)
            DrawerSelect(options: devices.map {
                             MenuOption(id: $0.deviceID, label: $0.name,
                                        description: Format.latency($0.latencyMS.map(Double.init)))
                         },
                         value: form.deviceID, ariaLabel: S.newSession.device, initiallyOpen: open,
                         onSelect: form.chooseDevice) {
                HStack(spacing: Space.sp3) {
                    OnlineDot(online: device?.online ?? false)
                    Text(device?.name ?? S.newSession.noDevices)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .css(FontSize.fs15)
                    Spacer(minLength: 0)
                    Text(device.map { Format.latency($0.latencyMS.map(Double.init)) } ?? "")
                        .css(FontSize.fs12, mono: true)
                        .foregroundStyle(Palette.inkSecondary)
                }
            }
            .disabled(devices.isEmpty)
        }
    }
}

/// A25: the agents no longer fit side by side under their names, so each is
/// its logo and the line under the control names the one that is chosen.
private struct AgentField: View {
    let form: NewSessionForm
    let device: Device?
    let agent: AgentInfo?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            FieldLabel(S.newSession.agent)
            Segmented(value: agent?.agent ?? "", options: (device?.agents ?? []).map { option in
                SegmentOption(value: option.agent, disabled: !option.available,
                              name: option.available
                                ? S.agentLabel(option.agent)
                                : "\(S.agentLabel(option.agent)) · \(S.newSession.agentUnavailable)") {
                    AgentLogo(agent: option.agent, size: 18)
                }
            }, ariaLabel: S.newSession.agent, onChange: form.chooseAgent)
            if let agent {
                agentLine(agent).padding(.top, Space.sp2)
            }
        }
    }

    private func agentLine(_ agent: AgentInfo) -> some View {
        let version = agent.version.flatMap { $0.isEmpty ? nil : " \($0)" } ?? ""
        let model = agent.defaultModel.flatMap { $0.isEmpty ? nil : " · \($0)" } ?? ""
        return (Text(S.agentLabel(agent.agent)) + Text(version + model).font(TextStyle(size: FontSize.fs12, mono: true).font))
            .css(FontSize.fs12)
            .foregroundStyle(Palette.inkSecondary)
    }
}
