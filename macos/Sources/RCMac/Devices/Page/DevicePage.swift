import RCCore
import SwiftUI

/// `/devices/:deviceId` — `web/src/features/devices/DevicePage.tsx` (A33): the
/// machine's own facts, then one card per coding agent it found, how each is
/// signed in and what is left of its quota (`docs/DESIGN.md` § "A device has a
/// page").
///
/// The hostname and the architecture live here alone: the row dropped both.
/// No client version and no build (A36). Rename and Revoke belong to the row and
/// are not repeated; what is here is what went wrong — the same "Updating…" and
/// "Update failed" the row says, and the Retry a failure earns.
public struct DevicePage: View {
    let deviceId: String
    @Environment(MacAppModel.self) private var model
    @State private var quota = DeviceQuota()
    @State private var retrying: Device?

    public init(deviceId: String) { self.deviceId = deviceId }

    public var body: some View {
        let device = model.device(deviceId)
        VStack(alignment: .leading, spacing: 0) {
            DevicePageBack { model.router.go(.devices) }
            if let device {
                DevicePageHead(device: device, quota: quota,
                               updateError: model.deviceUpdateErrors[device.deviceID],
                               servedBuild: model.connection.config.servedBuild,
                               onRetry: { retrying = device })
                agents(of: device)
            } else if model.connection.hasSnapshot {
                EmptyState(title: S.devicePage.gone, S.devicePage.goneHint).card()
            }
        }
        .modifier(RetryUpdateDialog(device: $retrying))
        .task(id: "\(deviceId):\(device?.online ?? false):\(quota.attempt)") {
            await quota.check(deviceID: deviceId, online: device?.online ?? false, channel: model.connection.channel)
        }
    }

    @ViewBuilder private func agents(of device: Device) -> some View {
        let agents = device.availableAgents
        if agents.isEmpty {
            // `.device-page-none`'s 8 collapses into the head's 24.
            Hint(S.devices.noAgents)
        } else {
            VStack(spacing: Space.sp3) {
                ForEach(agents) { agent in
                    AgentCard(agent: agent, accounts: quota.accounts[agent.agent] ?? agent.accounts, quota: quota)
                }
            }
        }
    }
}

/// `.device-page-back`: the way back to the list, in the secondary ink that the
/// pointer turns to the ink, hung 4 points into the gutter.
private struct DevicePageBack: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 2) {
                Icon(.chevronLeft, size: 15)
                Text(S.devicePage.back).css(FontSize.fs13)
            }
        }
        .buttonStyle(DevicePageBackStyle())
        .padding(.leading, -4)
        .padding(.bottom, Space.sp4)
    }
}

private struct DevicePageBackStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { DevicePageBackBody(configuration: configuration) }
}

private struct DevicePageBackBody: View {
    let configuration: ButtonStyleConfiguration
    @State private var isHovered = false

    var body: some View {
        configuration.label
            .foregroundStyle(isHovered ? Palette.ink : Palette.inkSecondary)
            .contentShape(Rectangle())
            .onHover { isHovered = $0 }
            .pointerStyle(.link)
    }
}

/// The page's head: the online dot and the name as the title, the machine's
/// facts in mono under it, the update notice with its Retry while there is one,
/// and Refresh at the trailing edge.
private struct DevicePageHead: View {
    let device: Device
    let quota: DeviceQuota
    let updateError: String?
    let servedBuild: String?
    let onRetry: () -> Void
    @Environment(\.layoutClass) private var layout

    var body: some View {
        HStack(alignment: .top, spacing: Space.sp4) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: Space.sp3) {
                    OnlineDot(online: device.online)
                    Text(device.name)
                        .css(layout.maxWidth760 ? FontSize.fs22 : FontSize.fs30, weight: .semibold, tracking: -0.02)
                        .accessibilityAddTraits(.isHeader)
                }
                Text("\(device.hostname) · \(device.platform.rawValue) · \(device.arch)")
                    .css(FontSize.fs13, mono: true)
                    .foregroundStyle(Palette.inkSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 2)
                if let notice = DeviceUpdateWords.notice(for: device, localError: updateError) {
                    noticeLine(notice)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Btn(S.devicePage.refresh, icon: .refreshCw) { quota.refresh() }
                .disabled(quota.status == .checking)
        }
        .padding(.bottom, Space.sp6)
    }

    private func noticeLine(_ notice: (text: String, failed: Bool)) -> some View {
        let blocked = DeviceUpdateWords.retryBlocked(device, servedBuild: servedBuild)
        return WrapRow(spacing: Space.sp3, lineSpacing: Space.sp2, centred: true) {
            Text(notice.text)
                .css(FontSize.fs13)
                .foregroundStyle(notice.failed ? Palette.danger : Palette.inkSecondary)
            if notice.failed {
                Btn(S.devices.retryUpdate, size: .small, action: onRetry)
                    .disabled(blocked != nil)
                    .help(blocked ?? "")
            }
        }
        .padding(.top, Space.sp2)
    }
}
