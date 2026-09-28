import RCCore
import SwiftUI

/// `/devices` — `web/src/features/devices/DevicesPage.tsx`: the title with how
/// many devices are connected, Add device, and one row per enrolled machine in
/// name order on one surface; or the empty card for an account with none.
public struct DevicesPage: View {
    @Environment(MacAppModel.self) private var model
    @Environment(\.previewStage) private var stage
    @State private var adding: AddDevicePairing?
    @State private var renaming: Device?
    @State private var revoking: Device?
    /// A36: the only update an app asks for is a retry of one that failed.
    @State private var retrying: Device?

    public init() {}

    public var body: some View {
        let devices = DeviceOrder.byName(model.connection.devices)
        let counts = DeviceOrder.sessionCounts(model.connection.sessions)
        VStack(alignment: .leading, spacing: 0) {
            PageHead(S.devices.title,
                     hint: S.devices.subtitleCount(devices.filter(\.online).count, devices.count)) {
                Btn(S.devices.add, icon: .plus, variant: .primary) { adding = AddDevicePairing() }
            }
            if devices.isEmpty {
                EmptyState(title: S.devices.empty, S.devices.emptyHint).card()
            } else {
                VStack(spacing: 0) {
                    ForEach(devices) { device in
                        DeviceRow(device: device, sessionCount: counts[device.deviceID] ?? 0,
                                  servedBuild: model.connection.config.servedBuild,
                                  updateError: model.deviceUpdateErrors[device.deviceID],
                                  menuOpen: stagedMenu(devices) == device.deviceID,
                                  refusesOnAppear: stagedRefusal(devices) == device.deviceID,
                                  onRename: { renaming = device },
                                  onRetryUpdate: { retrying = device },
                                  onRevoke: { revoking = device })
                    }
                }
                .surface()
            }
        }
        .modifier(AddDeviceModal(pairing: $adding))
        .modifier(DeviceDialogs(renaming: $renaming, revoking: $revoking, retrying: $retrying))
        .onAppear { openStaged(devices) }
    }
}

// MARK: - Preview stages

extension DevicesPage {
    /// What a render asks the page to show that normally takes a click.
    enum Stage: String {
        case menu = "devices.menu"
        case failedMenu = "devices.menu.failed"
        case refusal = "devices.refusal"
        case rename = "devices.rename"
        case revoke = "devices.revoke"
        case retry = "devices.retry"
        case add = "devices.add"
        case addManual = "devices.add.manual"
    }

    private var current: Stage? { stage.flatMap(Stage.init(rawValue:)) }

    /// The row whose menu a render draws open: the first, or the first whose
    /// update failed.
    private func stagedMenu(_ devices: [Device]) -> String? {
        switch current {
        case .menu: devices.first?.deviceID
        case .failedMenu: devices.first { $0.updateState == .failed }?.deviceID
        default: nil
        }
    }

    /// The first row whose click can open nothing, for the line that says why.
    private func stagedRefusal(_ devices: [Device]) -> String? {
        current == .refusal ? devices.first { !($0.online && $0.offersTerminal) }?.deviceID : nil
    }

    private func openStaged(_ devices: [Device]) {
        guard let current else { return }
        switch current {
        case .rename: renaming = devices.first
        case .revoke: revoking = devices.first
        case .retry: retrying = devices.first { $0.updateState == .failed }
        case .add, .addManual:
            let pairing = AddDevicePairing()
            pairing.manual = current == .addManual
            adding = pairing
        case .menu, .failedMenu, .refusal: break
        }
    }
}
