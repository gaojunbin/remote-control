import RCCore
import SwiftUI

/// The row's menu, behind its three dots, in one order (A38, rule 20):
/// Rename · Retry update (only while an update has failed, disabled with the
/// reason while it cannot be sent) · Show quota · Revoke.
struct DeviceRowMenu: View {
    let device: Device
    /// Why Retry update cannot be pressed; nil when it can.
    let retryBlocked: String?
    let failed: Bool
    let initiallyOpen: Bool
    let onRename: () -> Void
    let onRetryUpdate: () -> Void
    let onShowQuota: () -> Void
    let onRevoke: () -> Void

    var body: some View {
        Popover(align: .end, chevron: false, triggerStyle: MenuTriggerStyle(), ariaLabel: S.a11y.openMenu,
                initiallyOpen: initiallyOpen) {
            Icon(.moreHorizontal, size: 16)
        } content: { close in
            MenuList {
                MenuItemRow(S.common.rename) { close(); onRename() }
                if failed {
                    MenuItemRow(S.devices.retryUpdate, help: retryBlocked) { close(); onRetryUpdate() }
                        .disabled(retryBlocked != nil)
                }
                // A33's page, reached from here now that the row itself opens a
                // shell: the agents on the machine and what is left of each quota.
                MenuItemRow(S.devices.showQuota) { close(); onShowQuota() }
                MenuItemRow(S.common.revoke, danger: true) { close(); onRevoke() }
            }
        }
    }
}
