import SwiftUI

/// `web/src/components/Popover.tsx`: a trigger — a `.pill` unless the feature
/// gives it another style — with a 13-point chevron in the tertiary ink, and
/// the panel it opens and closes. The panel is placed against the window by
/// `PopoverPlacement`, so no clipped surface or scrolling pane can cut it off,
/// and it closes on Escape, a press outside it and its trigger, a second press
/// on the trigger, or the `close` its content is handed.
public struct Popover<Label: View, Content: View>: View {
    @State private var ownOpen: Bool
    private let external: Binding<Bool>?
    let align: PopoverAlign
    let side: PopoverSide
    let chevron: Bool
    let triggerStyle: AnyButtonStyle
    let ariaLabel: String?
    let label: Label
    let content: (_ close: @escaping () -> Void) -> Content
    @Environment(\.isEnabled) private var isEnabled

    /// A popover that keeps its own open state. `initiallyOpen` is how a
    /// preview stage shows it open without a click.
    public init(align: PopoverAlign = .start, side: PopoverSide = .bottom, chevron: Bool = true,
                triggerStyle: some ButtonStyle = PillStyle(), ariaLabel: String? = nil,
                initiallyOpen: Bool = false, @ViewBuilder label: () -> Label,
                @ViewBuilder content: @escaping (_ close: @escaping () -> Void) -> Content) {
        _ownOpen = State(initialValue: initiallyOpen)
        external = nil
        self.align = align
        self.side = side
        self.chevron = chevron
        self.triggerStyle = AnyButtonStyle(triggerStyle)
        self.ariaLabel = ariaLabel
        self.label = label()
        self.content = content
    }

    /// A popover whose open state belongs to the view that shows it.
    public init(isOpen: Binding<Bool>, align: PopoverAlign = .start, side: PopoverSide = .bottom,
                chevron: Bool = true, triggerStyle: some ButtonStyle = PillStyle(), ariaLabel: String? = nil,
                @ViewBuilder label: () -> Label,
                @ViewBuilder content: @escaping (_ close: @escaping () -> Void) -> Content) {
        _ownOpen = State(initialValue: false)
        external = isOpen
        self.align = align
        self.side = side
        self.chevron = chevron
        self.triggerStyle = AnyButtonStyle(triggerStyle)
        self.ariaLabel = ariaLabel
        self.label = label()
        self.content = content
    }

    private var isOpen: Binding<Bool> { external ?? $ownOpen }

    public var body: some View {
        let open = isOpen
        Button { open.wrappedValue.toggle() } label: {
            HStack(spacing: 6) {
                label
                if chevron {
                    Icon(.chevronDown, size: 13).foregroundStyle(Palette.inkTertiary)
                }
            }
        }
        .buttonStyle(triggerStyle)
        .environment(\.popoverIsOpen, open.wrappedValue)
        .modifier(AccessibleName(ariaLabel))
        .anchoredPanel(isPresented: open, align: align, side: side) {
            content { open.wrappedValue = false }
        }
        .onChange(of: isEnabled) { _, enabled in if !enabled { open.wrappedValue = false } }
    }
}

/// One choice of a `SelectMenu`.
public struct MenuOption: Identifiable, Sendable, Hashable {
    public let id: String
    public let label: String
    public let description: String?
    public let disabled: Bool

    public init(id: String, label: String, description: String? = nil, disabled: Bool = false) {
        self.id = id
        self.label = label
        self.description = description
        self.disabled = disabled
    }
}

/// `Menu` in `Popover.tsx`: a popover listing options as `.menu-item` rows,
/// the current one selected, which closes once one is chosen.
public struct SelectMenu<Label: View>: View {
    let options: [MenuOption]
    let value: String?
    let onSelect: (String) -> Void
    let ariaLabel: String
    let align: PopoverAlign
    let side: PopoverSide
    let initiallyOpen: Bool
    let label: Label

    public init(options: [MenuOption], value: String?, ariaLabel: String, align: PopoverAlign = .start,
                side: PopoverSide = .bottom, initiallyOpen: Bool = false, onSelect: @escaping (String) -> Void,
                @ViewBuilder label: () -> Label) {
        self.options = options
        self.value = value
        self.onSelect = onSelect
        self.ariaLabel = ariaLabel
        self.align = align
        self.side = side
        self.initiallyOpen = initiallyOpen
        self.label = label()
    }

    public var body: some View {
        Popover(align: align, side: side, ariaLabel: ariaLabel, initiallyOpen: initiallyOpen) {
            label
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

/// `aria-label`, where there is one; otherwise the control keeps the name its
/// content gives it.
struct AccessibleName: ViewModifier {
    let name: String?

    init(_ name: String?) { self.name = name }

    func body(content: Content) -> some View {
        if let name { content.accessibilityLabel(name) } else { content }
    }
}
