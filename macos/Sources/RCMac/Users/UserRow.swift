import RCCore
import SwiftUI

/// `UserRow.tsx` / `.account-row`: the username, `role · state`, the devices
/// it enrolled and its last sign-in, and the row's menu — Reset password,
/// Disable or Enable, Delete. `admin` is the operator: none of the three is
/// allowed on it, so it is given no menu.
struct UserRow: View {
    let user: UserRecord
    let actionable: Bool
    /// A render's stage opens this row's menu.
    let menuOpen: Bool
    let onResetPassword: () -> Void
    let onToggleState: () -> Void
    let onDelete: () -> Void
    @State private var isHovered = false
    @Environment(\.layoutClass) private var layout
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var disabled: Bool { user.state == .disabled }

    var body: some View {
        HStack(spacing: Space.sp4) {
            VStack(alignment: .leading, spacing: 0) {
                Text(user.username)
                    .css(FontSize.fs15, weight: .semibold, lineHeight: 1.4, tracking: -0.01)
                    // A disabled account stays legible and stops looking current.
                    .foregroundStyle(disabled ? Palette.inkSecondary : Palette.ink)
                UserMetaLine(user: user).padding(.top, 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if actionable { menu }
        }
        .padding(.horizontal, layout.maxWidth640 ? Space.sp4 : Space.sp5)
        .frame(minHeight: RowHeight.rowH)
        .background(isHovered ? Palette.hover : Color.clear)
        .contentShape(Rectangle())
        .onHover { isHovered = $0 }
        .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: isHovered)
        .environment(\.rowIsHovered, isHovered)
    }

    private var menu: some View {
        Popover(align: .end, chevron: false, triggerStyle: MenuTriggerStyle(), ariaLabel: S.a11y.openMenu,
                initiallyOpen: menuOpen) {
            Icon(.moreHorizontal, size: 16)
        } content: { close in
            MenuList {
                MenuItemRow(S.users.resetPassword) {
                    close()
                    onResetPassword()
                }
                MenuItemRow(disabled ? S.users.enable : S.users.disable) {
                    close()
                    onToggleState()
                }
                MenuItemRow(S.users.deleteAction, danger: true) {
                    close()
                    onDelete()
                }
            }
        }
    }
}

/// `.account-meta`: `role · state`, then the devices and the last sign-in in
/// the tertiary ink behind a faint dot — two spans that each keep to one line
/// and wrap under each other when the row is too narrow for both.
struct UserMetaLine: View {
    let user: UserRecord

    var body: some View {
        let lastSignIn = S.users.lastSignIn(user.lastLoginAt.map { Format.relativeAgo($0) } ?? S.users.never)
        SettingsFlexWrap(spacing: Space.sp2, lineSpacing: 2) {
            meta(S.users.meta(S.roleLabel(user.role.rawValue), S.userStateLabel(user.state.rawValue)))
                .foregroundStyle(Palette.inkSecondary)
            HStack(spacing: Space.sp2) {
                meta("·").foregroundStyle(Palette.lineStrong)
                meta("\(S.users.deviceCount(user.devices)) · \(lastSignIn)").foregroundStyle(Palette.inkTertiary)
            }
        }
    }

    private func meta(_ text: String) -> some View {
        Text(text).lineLimit(1).truncationMode(.tail).css(FontSize.fs12, lineHeight: 1.45)
    }
}
