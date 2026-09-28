import Foundation
import Observation
import RCCore
import SwiftUI

/// The numbers the follow rule reads from the scroll view.
struct TranscriptGeometry: Equatable {
    var offset: CGFloat = 0
    var contentHeight: CGFloat = 0
    var viewportHeight: CGFloat = 0

    init() {}

    init(_ geometry: ScrollGeometry) {
        offset = geometry.contentOffset.y + geometry.contentInsets.top
        contentHeight = geometry.contentSize.height + geometry.contentInsets.top + geometry.contentInsets.bottom
        viewportHeight = geometry.containerSize.height
    }

    var distanceToBottom: CGFloat { contentHeight - offset - viewportHeight }
    var atBottom: Bool { distanceToBottom <= ScrollFollow.bottomThreshold }
}

/// `useScrollFollow` bound to one scroll view: `ScrollFollow`'s rules, and what
/// a lazy stack adds to them. A `LazyVStack` places the end of its content
/// where it guessed the rows it has not laid out would be, and laying them out
/// moves the end again, so a scroll this view makes to the tail is repeated
/// until the tail is really on screen, and nothing the geometry does in the
/// meantime reads as the reader scrolling away.
@MainActor
@Observable
final class TranscriptScroll {
    enum Action: Equatable {
        case scrollToBottom(animated: Bool)
        case scrollTo(offset: CGFloat)
        case loadOlder
    }

    private(set) var following = true
    private(set) var missed = 0
    @ObservationIgnored private var state = ScrollFollow(rows: ScrollFollow.Rows(revision: "", redraw: .simple,
                                                                              firstKey: nil, lastKey: nil))
    @ObservationIgnored private var geometry = TranscriptGeometry()
    /// How many more times a scroll to the tail may be repeated, while one is
    /// on its way.
    @ObservationIgnored private var pinning = 0
    /// The reader has a hand on the transcript.
    @ObservationIgnored private var reading = false
    @ObservationIgnored private var started = false

    /// How many times one trip to the tail may be repeated.
    static let pinAttempts = 8

    /// The rows changed: new blocks, a delta, a redraw. The first call only
    /// learns what the rows are and pins the tail, as the web's first render
    /// scrolls to the bottom.
    func rowsChanged(_ rows: ScrollFollow.Rows) -> [Action] {
        guard started else {
            started = true
            state = ScrollFollow(rows: rows)
            return pin(animated: false)
        }
        let scrolls = state.rowsChanged(to: rows)
        publish()
        return scrolls ? pin(animated: false) : []
    }

    /// The jump-to-latest button: the count goes now, the button when the tail
    /// is on screen.
    func jump() -> [Action] {
        state.jumpStarted()
        publish()
        return pin(animated: true)
    }

    func phaseChanged(_ phase: ScrollPhase) {
        reading = phase == .tracking || phase == .interacting || phase == .decelerating
        if reading { pinning = 0 }
    }

    func geometryChanged(to next: TranscriptGeometry) -> [Action] {
        let previous = geometry
        geometry = next
        var actions: [Action] = []
        if let delta = state.measured(contentHeight: next.contentHeight) {
            actions.append(.scrollTo(offset: next.offset + delta))
        }
        if pinning > 0 {
            if next.atBottom {
                pinning = 0
                state.reachedBottom()
                publish()
            } else if next.contentHeight != previous.contentHeight || next.offset != previous.offset {
                pinning -= 1
                actions.append(.scrollToBottom(animated: false))
            }
            return actions
        }
        guard next.offset != previous.offset else { return actions }
        let reachedTop = state.scrolled(offset: next.offset, contentHeight: next.contentHeight,
                                        viewportHeight: next.viewportHeight)
        publish()
        if reachedTop { actions.append(.loadOlder) }
        return actions
    }

    private func pin(animated: Bool) -> [Action] {
        guard !reading || animated else { return [] }
        pinning = Self.pinAttempts
        return [.scrollToBottom(animated: animated)]
    }

    private func publish() {
        if following != state.following { following = state.following }
        if missed != state.missed { missed = state.missed }
    }
}

/// The rows a transcript draws, worked out once per transcript version and
/// detail level rather than on every pass through the view.
@MainActor
final class TranscriptSelectionCache {
    private var key: (version: Int, detail: TimelineDetail)?
    private var value = TranscriptSelection(timeline: Timeline(), detail: .simple)

    func selection(_ timeline: Timeline, detail: TimelineDetail) -> TranscriptSelection {
        if let key, key.version == timeline.version, key.detail == detail { return value }
        value = TranscriptSelection(timeline: timeline, detail: detail)
        key = (timeline.version, detail)
        return value
    }
}
