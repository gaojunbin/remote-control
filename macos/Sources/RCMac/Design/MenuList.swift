import SwiftUI

/// `.menu`: the list inside a popover panel, scrolling past 320 points.
public struct MenuList<Content: View>: View {
    let content: Content

    public init(@ViewBuilder content: () -> Content) { self.content = content() }

    public var body: some View {
        ViewThatFits(in: .vertical) {
            VStack(alignment: .leading, spacing: 0) { content }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) { content }
            }
            .frame(maxHeight: 320)
        }
    }
}

/// `.menu-item`: a row of a menu — a button, or a link when it goes somewhere,
/// both drawn alike — with a 14-point label and an optional 12-point line
/// under it. The pointer tints it `--surface-hover`, the selected one holds
/// `--surface-muted`, and a disabled one fades. `.danger-text` turns the label
/// the danger ink.
public struct MenuItemRow: View {
    let label: String
    let description: String?
    let selected: Bool
    let danger: Bool
    let help: String?
    let action: () -> Void

    public init(_ label: String, description: String? = nil, selected: Bool = false, danger: Bool = false,
                help: String? = nil, action: @escaping () -> Void) {
        self.label = label
        self.description = description
        self.selected = selected
        self.danger = danger
        self.help = help
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .css(FontSize.fs14)
                    .foregroundStyle(danger ? Palette.danger : Palette.ink)
                if let description {
                    Text(description)
                        .css(FontSize.fs12)
                        .foregroundStyle(Palette.inkSecondary)
                }
            }
            .multilineTextAlignment(.leading)
        }
        .buttonStyle(MenuItemStyle(selected: selected))
        .help(help ?? "")
    }
}

struct MenuItemStyle: ButtonStyle {
    let selected: Bool

    func makeBody(configuration: Configuration) -> some View {
        MenuItemBody(configuration: configuration, selected: selected)
    }
}

private struct MenuItemBody: View {
    let configuration: ButtonStyleConfiguration
    let selected: Bool
    @Environment(\.isEnabled) private var isEnabled
    @State private var isHovered = false

    var body: some View {
        configuration.label
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, Space.sp2)
            .padding(.horizontal, 10)
            .background(RoundedRectangle(cornerRadius: Radius.sm, style: .circular).fill(fill))
            .contentShape(Rectangle())
            .opacity(isEnabled ? 1 : 0.45)
            .onHover { isHovered = $0 }
            .pointerStyle(isEnabled ? .link : nil)
    }

    private var fill: Color {
        if isEnabled && isHovered { return Palette.surfaceHover }
        return selected ? Palette.surfaceMuted : Color.clear
    }
}

/// The three-dot trigger a device row and an account row open their menu with
/// (`.device-menu-trigger`, `.account-menu-trigger`): a 28-point circle with
/// no tint, the icon in the tertiary ink until the row or the pointer is over
/// it or its menu is open, and the pill's hover tint under the pointer.
public struct MenuTriggerStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        MenuTriggerBody(configuration: configuration)
    }
}

private struct MenuTriggerBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.rowIsHovered) private var rowIsHovered
    @Environment(\.popoverIsOpen) private var isOpen
    @State private var isHovered = false

    var body: some View {
        let lit = isEnabled && (isHovered || configuration.isPressed)
        configuration.label
            .frame(width: 28, height: 28)
            .foregroundStyle(lit || isOpen || rowIsHovered ? Palette.ink : Palette.inkTertiary)
            .background(Circle().fill(lit ? Palette.surfaceActive : Color.clear))
            .contentShape(Circle())
            .onHover { isHovered = $0 }
            .pointerStyle(isEnabled ? .link : nil)
    }
}

extension EnvironmentValues {
    /// Whether the list row around a control is under the pointer, for the
    /// controls a row's hover lights up (`.device-row:hover .device-menu-trigger`).
    @Entry public var rowIsHovered = false
}
