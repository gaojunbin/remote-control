import CoreGraphics
import RCCore

/// `web/src/features/chat/useScrollFollow.ts`: the transcript follows the tail
/// while the reader has not scrolled away, counts the new blocks they missed
/// otherwise, and keeps their rows where they were when an older history page
/// is prepended (`docs/DESIGN.md` § "Reading position").
///
/// Pure arithmetic over what the scroll view reports and what the rows are, so
/// the rules are checked without a window. The view applies what it answers.
public struct ScrollFollow: Sendable, Equatable {
    /// Within this of the foot still counts as at the bottom.
    public static let bottomThreshold: CGFloat = 56
    /// Within this of the top asks for the previous page of history.
    public static let topTrigger: CGFloat = 120

    /// What the rows are, as the web's hook reads them on every render.
    public struct Rows: Sendable, Equatable {
        /// Changes on every event, streaming and unconfirmed sends included.
        public var revision: String
        /// Changes when the same blocks are drawn differently — the detail
        /// level. A redraw is not new content: it counts nothing and moves no
        /// anchor.
        public var redraw: TimelineDetail
        public var firstKey: String?
        public var lastKey: String?

        public init(revision: String, redraw: TimelineDetail, firstKey: String?, lastKey: String?) {
            self.revision = revision
            self.redraw = redraw
            self.firstKey = firstKey
            self.lastKey = lastKey
        }
    }

    public private(set) var following = true
    /// Blocks that landed while the reader was away, not streaming deltas.
    public private(set) var missed = 0
    /// The content height a prepended page is to be measured against, until
    /// the scroll view reports the height it has with the page in it.
    public private(set) var anchorHeight: CGFloat?
    private var rows: Rows
    private var contentHeight: CGFloat = 0

    public init(rows: Rows) { self.rows = rows }

    /// `onScroll`: the reader, or a scroll of this view's, moved the offset.
    /// Answers whether the top is close enough to ask for older history.
    public mutating func scrolled(offset: CGFloat, contentHeight: CGFloat, viewportHeight: CGFloat) -> Bool {
        let distance = contentHeight - offset - viewportHeight
        following = distance <= Self.bottomThreshold
        if following { missed = 0 }
        return offset <= Self.topTrigger
    }

    /// The rows changed. The web runs three effects, in this order: the anchor
    /// of a prepended page, the follow, and the count. Answers whether the view
    /// is to scroll to the bottom.
    public mutating func rowsChanged(to next: Rows) -> Bool {
        let redrawn = next.redraw != rows.redraw
        // Only a prepended history page moves the anchor. Content appended
        // below the viewport must not drag the reader down, and neither must a
        // redraw: the rows around the reader changed because they asked for it.
        let prepended = next.firstKey != nil && next.firstKey != rows.firstKey
        if prepended && !redrawn && !following { anchorHeight = contentHeight }

        let scrolls = following && (next.revision != rows.revision || redrawn)
        if scrolls { missed = 0 }

        // Count blocks, not streaming deltas: "2 new" for two messages, not
        // once per flush. A redraw starts the count again.
        if redrawn {
            missed = 0
        } else if next.lastKey != rows.lastKey, next.lastKey != nil, !following {
            missed += 1
        }
        rows = next
        return scrolls
    }

    /// The scroll view reported the content's height. Answers how far to move
    /// the offset down so the rows a prepended page pushed are back in place.
    public mutating func measured(contentHeight height: CGFloat) -> CGFloat? {
        defer { contentHeight = height }
        guard let anchor = anchorHeight, height != contentHeight else { return nil }
        anchorHeight = nil
        let delta = height - anchor
        return delta > 0 ? delta : nil
    }

    /// The view scrolled to the tail itself: following again, nothing missed.
    public mutating func reachedBottom() {
        following = true
        missed = 0
    }

    /// The jump-to-latest button was pressed: the count goes at once, and the
    /// button itself goes when the tail is on screen.
    public mutating func jumpStarted() { missed = 0 }
}
