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

    /// Who is moving the transcript when its geometry changes. A scroll view
    /// reports the same numbers whether a finger dragged it, a row grew under
    /// it, or the view itself scrolled to the tail, and the three mean
    /// different things.
    public enum ReaderMotion: Sendable, Hashable {
        /// Nobody: rows arrived, the keyboard opened, a row settled.
        case still
        /// The reader: a drag, a fling still decelerating.
        case reading
        /// This view: a scroll it started is on its way.
        case animating
    }

    /// What the timeline does about one geometry change.
    public enum TailAction: Sendable, Hashable {
        case none
        /// Set whether the timeline follows new content.
        case follow(Bool)
        /// Scroll to the tail and keep following.
        case scrollToTail
    }

    /// The rule for one geometry change. `rangeChanged` is whether the
    /// scrollable range moved — rows arrived or the container was resized —
    /// and `atBottom` and `following` are as they stand when it arrives.
    ///
    /// The reader always wins: while a finger is on the transcript, or a fling
    /// is still running, the numbers are theirs, whatever the content did at
    /// the same moment, and nothing scrolls under them. That is what keeps a
    /// transcript whose rows are still settling after a turn — or being laid
    /// out lazily as history scrolls into view — from snapping back to the
    /// foot each time its height moves while someone is scrolling up.
    public static func decide(rangeChanged: Bool, atBottom: Bool, following: Bool,
                              motion: ReaderMotion) -> TailAction {
        switch motion {
        case .reading:
            return .follow(atBottom)
        case .animating:
            // A scroll this view started can only confirm that it arrived.
            return atBottom ? .follow(true) : .none
        case .still:
            guard rangeChanged else { return .follow(atBottom) }
            // Someone at the foot stays there; someone away is left where they
            // are, unless the range shrank until nothing scrolls any more.
            if following { return .scrollToTail }
            return atBottom ? .follow(true) : .none
        }
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
