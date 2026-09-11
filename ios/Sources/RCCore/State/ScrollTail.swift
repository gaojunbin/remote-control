import Foundation

/// Where the reader is in a scrolling transcript, worked out from the numbers a
/// scroll view reports. Pure arithmetic, so the rule that decides whether the
/// timeline follows new content is checked without a simulator.
public enum ScrollTail {
    /// How close to the foot of the content still counts as being at the
    /// bottom. A row that settles a few points short of the end must not stop
    /// the timeline from following what arrives next.
    public static let threshold: Double = 40

    /// True when the visible rectangle ends within `threshold` of the content.
    /// A transcript shorter than its container has no bottom to leave, so it is
    /// always at the bottom.
    ///
    /// - Parameters:
    ///   - contentHeight: the scrolled content, its insets included.
    ///   - containerHeight: the height of the window onto that content.
    ///   - offset: how far the content has been scrolled, measured the same way.
    public static func isAtBottom(contentHeight: Double, containerHeight: Double,
                                  offset: Double, threshold: Double = threshold) -> Bool {
        offset >= max(0, contentHeight - containerHeight) - threshold
    }

    /// What the jump-to-latest button counts, or nil when nothing arrived while
    /// the reader was away. Blocks, never streaming deltas, so a long answer is
    /// one update rather than two hundred.
    public static func badge(updates: Int) -> String? {
        guard updates > 0 else { return nil }
        return updates > 99 ? "99+" : String(updates)
    }

    /// The same count as VoiceOver reads it, appended to the button's label.
    public static func spokenBadge(updates: Int) -> String? {
        guard updates > 0 else { return nil }
        return "\(updates) new update\(updates == 1 ? "" : "s")"
    }
}
