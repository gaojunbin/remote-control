import SwiftUI

/// What kind of overlay an entry is, which decides its backdrop, where its
/// panel goes and how it closes.
enum OverlayKind {
    /// `.overlay` + `.modal`: centred, `width` at most.
    case modal(width: CGFloat)
    /// `.drawer-overlay` + `.drawer`: the right-hand drawer.
    case drawer
    /// `.popover-panel`, anchored to its trigger.
    case popover(anchor: Anchor<CGRect>, align: PopoverAlign, side: PopoverSide)

    var isPopover: Bool {
        if case .popover = self { return true }
        return false
    }
}

/// One overlay a view asked for, carried up to the window's overlay layer as a
/// preference so its content stays live with the state of the view that asked.
struct OverlayEntry: Identifiable {
    let id: UUID
    let kind: OverlayKind
    /// When it opened, which decides what Escape closes: the newest first.
    let openedAt: Int
    let content: AnyView
    let dismiss: @MainActor () -> Void
}

struct OverlayEntriesKey: PreferenceKey {
    static var defaultValue: [OverlayEntry] { [] }

    static func reduce(value: inout [OverlayEntry], nextValue: () -> [OverlayEntry]) {
        value.append(contentsOf: nextValue())
    }
}

/// Hands out the order overlays open in.
@MainActor
enum OverlayClock {
    private static var last = 0

    static func next() -> Int {
        last += 1
        return last
    }
}

extension EnvironmentValues {
    /// Whether the popover this control triggers is open: `aria-expanded`,
    /// which a trigger style can light itself from.
    @Entry public var popoverIsOpen = false
}
