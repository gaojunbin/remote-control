import RCMac
import SwiftUI

/// A row menu open, where the web's Devices page has the first row's: the
/// three-dot trigger 28 points square at (1108, 202), the panel hung from its
/// trailing edge. The reference for every row menu the lists draw.
struct MenuGallery: View {
    var body: some View {
        ZStack(alignment: .topLeading) {
            Palette.canvas
            Popover(align: .end, chevron: false, triggerStyle: MenuTriggerStyle(), ariaLabel: S.a11y.openMenu,
                    initiallyOpen: true) {
                Icon(.moreHorizontal, size: 16)
            } content: { close in
                MenuList {
                    MenuItemRow(S.common.rename) { close() }
                    MenuItemRow(S.devices.retryUpdate) { close() }
                    MenuItemRow(S.devices.showQuota) { close() }
                    MenuItemRow(S.common.revoke, danger: true) { close() }
                }
            }
            .offset(x: 1108, y: 202)
        }
    }
}
