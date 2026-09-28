import SwiftUI

/// The web's media queries, read from the window's content width: every
/// screen follows the same breakpoints its stylesheet does. `max-width: N px`
/// holds at N and below, so `maxWidth760` is true at 760 points.
public struct LayoutClass: Equatable, Sendable {
    public var width: CGFloat
    public var height: CGFloat

    public init(width: CGFloat, height: CGFloat) {
        self.width = width
        self.height = height
    }

    /// `@media (max-width: …)`.
    public func maxWidth(_ px: CGFloat) -> Bool { width <= px }

    /// Below this the chat is one pane with a back button (`chat.css`).
    public var maxWidth1023: Bool { maxWidth(1023) }
    /// The compact topbar, pages and device rows (`layout.css`, `devices.css`).
    public var maxWidth760: Bool { maxWidth(760) }
    /// Full-width drawer, modals as bottom sheets, and the narrow Sessions,
    /// Settings and Users layouts.
    public var maxWidth640: Bool { maxWidth(640) }
    /// The phone-sized device rows, device page and terminal.
    public var maxWidth480: Bool { maxWidth(480) }
    /// The tightest topbar.
    public var maxWidth420: Bool { maxWidth(420) }
}

extension EnvironmentValues {
    @Entry public var layoutClass = LayoutClass(width: 1280, height: 860)
}
