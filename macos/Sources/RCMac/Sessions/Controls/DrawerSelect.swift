import SwiftUI

/// The New session drawer's menus: the web's `Menu` with its pill stretched to
/// the drawer's width, 46 points tall on a 12-point radius, the choice at the
/// leading edge and the chevron at the trailing one (`.drawer .popover-root >
/// .pill`). The list it opens is `SelectMenu`'s.
struct DrawerSelect<Label: View>: View {
    let options: [MenuOption]
    let value: String?
    let ariaLabel: String
    var align: PopoverAlign = .start
    var initiallyOpen = false
    let onSelect: (String) -> Void
    @ViewBuilder let label: Label

    var body: some View {
        Popover(align: align, triggerStyle: DrawerSelectStyle(), ariaLabel: ariaLabel, initiallyOpen: initiallyOpen) {
            label.frame(maxWidth: .infinity, alignment: .leading)
        } content: { close in
            MenuList {
                ForEach(options) { option in
                    MenuItemRow(option.label, description: option.description, selected: value == option.id) {
                        onSelect(option.id)
                        close()
                    }
                    .disabled(option.disabled)
                }
            }
            .accessibilityLabel(ariaLabel)
        }
    }
}

/// `.pill` as the drawer draws it.
struct DrawerSelectStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { DrawerSelectBody(configuration: configuration) }
}

private struct DrawerSelectBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.isFocused) private var isFocused
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovered = false

    var body: some View {
        let lit = isEnabled && (isHovered || configuration.isPressed)
        let shape = RoundedRectangle(cornerRadius: isFocused ? 4 : Radius.md, style: .circular)
        HStack(spacing: 6) { configuration.label }
            .font(TextStyle(size: FontSize.fs13).font)
            .lineLimit(1)
            .padding(.horizontal, Space.sp4)
            .frame(maxWidth: .infinity)
            .frame(height: 46)
            .foregroundStyle(isEnabled ? Palette.ink : Palette.inkTertiary)
            .background(shape.fill(lit ? Palette.surfaceActive : Palette.surfaceMuted))
            .contentShape(shape)
            .opacity(isEnabled ? 1 : 0.6)
            .focusOutline(isFocused)
            .onHover { isHovered = $0 }
            .pointerStyle(isEnabled ? .link : nil)
            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: lit)
    }
}
