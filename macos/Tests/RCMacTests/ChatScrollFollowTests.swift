import CoreGraphics
import RCCore
import Testing
@testable import RCMac

/// `web/tests/useScrollFollow.test.tsx`, over the rule the transcript applies:
/// a 400-point window onto content that grows the way events make it grow.
@Suite("Chat scroll follow")
struct ChatScrollFollowTests {
    private static let viewport: CGFloat = 400

    private struct Harness {
        var follow: ScrollFollow
        var offset: CGFloat = 0
        var height: CGFloat = 1000

        init(revision: Int, first: String?, last: String?, redraw: TimelineDetail = .detailed) {
            follow = ScrollFollow(rows: ScrollFollow.Rows(revision: "\(revision)", redraw: redraw,
                                                          firstKey: first, lastKey: last))
            _ = follow.measured(contentHeight: height)
            follow.reachedBottom()
            offset = height - ChatScrollFollowTests.viewport
        }

        /// The reader scrolls to `top`. Answers whether older history was asked for.
        @discardableResult
        mutating func scroll(to top: CGFloat) -> Bool {
            offset = top
            return follow.scrolled(offset: top, contentHeight: height, viewportHeight: ChatScrollFollowTests.viewport)
        }

        /// A render with new rows and the content at `newHeight`, then the
        /// view does what the rule answers.
        mutating func update(revision: Int, first: String?, last: String?, redraw: TimelineDetail = .detailed,
                             height newHeight: CGFloat? = nil) {
            let scrolls = follow.rowsChanged(to: ScrollFollow.Rows(revision: "\(revision)", redraw: redraw,
                                                                  firstKey: first, lastKey: last))
            if let newHeight { height = newHeight }
            if let delta = follow.measured(contentHeight: height) { offset += delta }
            if scrolls {
                offset = height - ChatScrollFollowTests.viewport
                follow.reachedBottom()
            }
        }
    }

    @Test func itStartsPinnedToTheBottom() {
        let h = Harness(revision: 1, first: "a", last: "a")
        #expect(h.follow.following)
        #expect(h.follow.missed == 0)
        #expect(h.offset == 600)
    }

    @Test func itLetsGoWhenTheReaderScrollsUpAndTakesHoldAgainAtTheBottom() {
        var h = Harness(revision: 1, first: "a", last: "a")
        h.scroll(to: 200)
        #expect(!h.follow.following)
        h.scroll(to: 600)
        #expect(h.follow.following)
    }

    @Test func aBlockAppendedBelowDoesNotDragTheReaderDown() {
        var h = Harness(revision: 1, first: "a", last: "c")
        h.scroll(to: 200)
        h.update(revision: 2, first: "a", last: "d", height: 1400)
        #expect(h.offset == 200)
    }

    @Test func aPrependedHistoryPageKeepsTheAnchor() {
        var h = Harness(revision: 5, first: "c", last: "e")
        h.scroll(to: 100)
        h.update(revision: 5, first: "a", last: "e", height: 1600)
        #expect(h.offset == 700)
    }

    @Test func itCountsBlocksNotStreamingDeltas() {
        var h = Harness(revision: 1, first: "a", last: "a")
        h.scroll(to: 200)
        for revision in 2...11 { h.update(revision: revision, first: "a", last: "a") }
        #expect(h.follow.missed == 0)
        h.update(revision: 12, first: "a", last: "b")
        #expect(h.follow.missed == 1)
        h.update(revision: 13, first: "a", last: "c")
        #expect(h.follow.missed == 2)
    }

    @Test func aPrependedPageIsNoMissedUpdate() {
        var h = Harness(revision: 5, first: "c", last: "e")
        h.scroll(to: 100)
        h.update(revision: 5, first: "a", last: "e", height: 1600)
        #expect(h.follow.missed == 0)
    }

    @Test func itFollowsTheTailWhileAttachedAndResetsTheCountOnReturn() {
        var h = Harness(revision: 1, first: "a", last: "a")
        h.update(revision: 2, first: "a", last: "b", height: 1200)
        #expect(h.offset == 800)
        #expect(h.follow.missed == 0)
        h.scroll(to: 100)
        h.update(revision: 3, first: "a", last: "c", height: 1400)
        #expect(h.follow.missed == 1)
        #expect(h.offset == 100)
        h.scroll(to: 1000)
        #expect(h.follow.missed == 0)
        #expect(h.follow.following)
    }

    @Test func aRedrawOfTheSameBlocksCountsNothing() {
        var h = Harness(revision: 1, first: "a", last: "c", redraw: .simple)
        h.scroll(to: 200)
        h.update(revision: 2, first: "a", last: "d", redraw: .simple)
        #expect(h.follow.missed == 1)
        h.update(revision: 2, first: "thinking-1", last: "tool-9", redraw: .detailed, height: 2200)
        #expect(h.follow.missed == 0)
        h.update(revision: 3, first: "thinking-1", last: "e", redraw: .detailed, height: 2400)
        #expect(h.follow.missed == 1)
    }

    @Test func aRedrawThatChangesTheFirstRowMovesNoAnchor() {
        var h = Harness(revision: 5, first: "c", last: "e", redraw: .simple)
        h.scroll(to: 100)
        h.update(revision: 5, first: "a", last: "e", redraw: .detailed, height: 1600)
        #expect(h.offset == 100)
    }

    @Test func reachingTheTopAsksForOlderHistory() {
        var h = Harness(revision: 1, first: "a", last: "a")
        let nearTheTop = h.scroll(to: 40)
        let further = h.scroll(to: 300)
        #expect(nearTheTop)
        #expect(!further)
    }

    @Test func theButtonTakesTheCountAwayAtOnce() {
        var h = Harness(revision: 1, first: "a", last: "a")
        h.scroll(to: 100)
        h.update(revision: 2, first: "a", last: "b")
        #expect(h.follow.missed == 1)
        h.follow.jumpStarted()
        #expect(h.follow.missed == 0)
        #expect(!h.follow.following)
    }
}
