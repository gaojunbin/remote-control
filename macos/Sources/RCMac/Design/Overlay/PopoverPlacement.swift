import CoreGraphics

/// `web/src/components/popoverPlacement.ts`: where a popover panel goes, in
/// window coordinates.
///
/// The panel is drawn in the window's overlay layer rather than inside its
/// trigger, so it is placed against the window, which is what keeps it out of
/// every clipped list surface and every scrolling pane it is anchored in.
public enum PopoverAlign: Sendable { case start, end }
public enum PopoverSide: Sendable { case top, bottom }

public enum PopoverPlacement {
    /// Gap between the trigger and the panel, and the margin kept to the window.
    static let gap: CGFloat = 6
    static let edge: CGFloat = 8

    public struct Placement: Equatable, Sendable {
        public let left: CGFloat
        /// Exactly one of the two is set; the other side is left free.
        public let top: CGFloat?
        public let bottom: CGFloat?
    }

    /// Anchors the panel to `trigger`, flipping to the other side only when the
    /// asked-for one cannot hold the panel and the other one can hold more of
    /// it, and keeping the panel inside the window horizontally.
    public static func place(trigger: CGRect, panel: CGSize, viewport: CGSize,
                             align: PopoverAlign, side: PopoverSide) -> Placement {
        let below = viewport.height - trigger.maxY - gap - edge
        let above = trigger.minY - gap - edge

        var placeBelow = side == .bottom
        if placeBelow, panel.height > below, above > below { placeBelow = false }
        else if !placeBelow, panel.height > above, below > above { placeBelow = true }

        let preferred = align == .start ? trigger.minX : trigger.maxX - panel.width
        let left = min(max(preferred, edge), max(edge, viewport.width - panel.width - edge))

        return Placement(left: jsRound(left),
                         top: placeBelow ? jsRound(trigger.maxY + gap) : nil,
                         bottom: placeBelow ? nil : jsRound(viewport.height - trigger.minY + gap))
    }

    /// The panel's top edge, whichever side it was placed on.
    public static func top(of placement: Placement, panelHeight: CGFloat, viewportHeight: CGFloat) -> CGFloat {
        placement.top ?? (viewportHeight - (placement.bottom ?? 0) - panelHeight)
    }

    /// `Math.round`.
    private static func jsRound(_ value: CGFloat) -> CGFloat { (value + 0.5).rounded(.down) }
}
