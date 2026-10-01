package com.junbingao.remotecontrol.win.chat.timeline

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.state.Timeline
import com.junbingao.remotecontrol.core.state.TimelineDetail

/**
 * The numbers the follow rule reads from the transcript's list, in points: how far the reader is
 * from the top, how tall the content is, how tall the window onto it is — and the first row on
 * screen, which a prepended page is anchored to. A lazy list knows the rows it has laid out and
 * guesses the rest from their average, as SwiftUI's lazy stack does.
 */
data class TranscriptGeometry(
    val offset: Float = 0f,
    val contentHeight: Float = 0f,
    val viewportHeight: Float = 0f,
    /** The first row on screen and where its top is, in pixels from the list's own origin. */
    val firstRow: Pair<Any, Int>? = null,
    /** The last row is laid out, so `contentHeight` is measured rather than guessed. */
    val measuredToTheEnd: Boolean = true,
) {
    val distanceToBottom: Float get() = contentHeight - offset - viewportHeight
    val atBottom: Boolean get() = distanceToBottom <= ScrollFollow.bottomThreshold

    companion object {
        /** `isRow` tells a row of the transcript from a note or the status line, by its key. */
        fun of(info: LazyListLayoutInfo, density: Float, isRow: (Any) -> Boolean): TranscriptGeometry {
            val items = info.visibleItemsInfo
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            if (items.isEmpty()) return TranscriptGeometry(0f, viewport / density, viewport / density)
            val first = items.first()
            val last = items.last()
            val spacing = info.mainAxisItemSpacing
            val laid = (last.offset + last.size - first.offset - spacing * (items.size - 1)).toFloat()
            val step = laid / items.size + spacing
            val above = first.index * step - first.offset
            val below = (info.totalItemsCount - 1 - last.index) * step + last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
            val row = items.firstOrNull { isRow(it.key) }
            return TranscriptGeometry(
                offset = above / density,
                contentHeight = (above + viewport + below) / density,
                viewportHeight = viewport / density,
                firstRow = row?.let { it.key to it.offset },
                measuredToTheEnd = last.index == info.totalItemsCount - 1,
            )
        }
    }
}

/**
 * `useScrollFollow` bound to one lazy list: `ScrollFollow`'s rules, and what a lazy list adds to
 * them. A scroll this view makes to the tail is repeated until the tail is really on screen —
 * a row laid out for the first time can still grow — and nothing the geometry does in the
 * meantime reads as the reader scrolling away.
 *
 * A prepended page keeps the reader's rows where they were: the list holds its first visible row
 * by key, and where that was a note rather than a row — "Loading…" at the very top — the first row
 * that was on screen is put back where it stood (`Action.Restore`), which is what the Mac's anchor
 * height does for its stack.
 */
class TranscriptScroll {
    sealed interface Action {
        data class ScrollToBottom(val animated: Boolean) : Action

        /** Put the row with `key` back at `offset`, in pixels from the list's own origin. */
        data class Restore(val key: Any, val offset: Int) : Action
        data object LoadOlder : Action
    }

    var following by mutableStateOf(true)
        private set
    var missed by mutableIntStateOf(0)
        private set
    private var state = ScrollFollow(ScrollFollow.Rows(revision = "", redraw = TimelineDetail.simple, firstKey = null, lastKey = null))
    private var geometry = TranscriptGeometry()

    /** How many more times a scroll to the tail may be repeated, while one is on its way. */
    private var pinning = 0

    /** The reader has a hand on the transcript. */
    private var reading = false
    private var started = false

    /** The first row on screen when a prepended page arrived, until the list has laid the page out. */
    private var anchor: Pair<Any, Int>? = null

    /** Whether the reader was last told apart from the tail on the list's guess at rows it had not laid out. */
    private var judgedOnAGuess = false

    /**
     * The rows changed: new blocks, a delta, a redraw. The first call only learns what the rows are
     * and pins the tail, as the web's first render scrolls to the bottom.
     */
    fun rowsChanged(rows: ScrollFollow.Rows): List<Action> {
        if (!started) {
            started = true
            state = ScrollFollow(rows)
            return pin(animated = false)
        }
        val hadAnchor = state.anchorHeight != null
        val scrolls = state.rowsChanged(rows)
        if (!hadAnchor && state.anchorHeight != null) anchor = geometry.firstRow
        publish()
        return if (scrolls) pin(animated = false) else emptyList()
    }

    /** The jump-to-latest button: the count goes now, the button when the tail is on screen. */
    fun jump(): List<Action> {
        state.jumpStarted()
        publish()
        return pin(animated = true)
    }

    /**
     * The list's own move to the tail ended at the tail. A list already at its end reports no new
     * geometry for a move that changed nothing, so the move says so itself, and nothing is left
     * armed to pull a later scroll back down.
     */
    fun reachedTail() {
        if (pinning == 0) return
        pinning = 0
        state.reachedBottom()
        publish()
    }

    fun phaseChanged(reading: Boolean) {
        this.reading = reading
        if (reading) pinning = 0
    }

    fun geometryChanged(next: TranscriptGeometry): List<Action> {
        val previous = geometry
        geometry = next
        val actions = mutableListOf<Action>()
        // The list measures the page it was handed against its own origin; the delta the rule
        // answers is the Mac's to apply, and the list has already applied it by key.
        state.measured(contentHeight = next.contentHeight)
        anchor?.let { held ->
            if (next.contentHeight != previous.contentHeight) {
                anchor = null
                if (next.firstRow != held) actions += Action.Restore(held.first, held.second)
            }
        }
        if (pinning > 0) {
            if (next.atBottom) {
                pinning = 0
                state.reachedBottom()
                publish()
            } else if (next.contentHeight != previous.contentHeight || next.offset != previous.offset) {
                pinning -= 1
                actions += Action.ScrollToBottom(animated = false)
            }
            return actions
        }
        if (next.offset == previous.offset) {
            // A lazy list guesses the rows below the last one it laid out from the average of
            // those it did, so a short last row — the status line — can read as a reader far from
            // the tail. Once the list knows the real end, the reader is judged again where they are.
            if (judgedOnAGuess && next.contentHeight != previous.contentHeight) judge(next)
            return actions
        }
        if (judge(next)) actions += Action.LoadOlder
        return actions
    }

    /** `onScroll`: following or not by how far the reader is from the tail. Answers whether the top is near enough for older history. */
    private fun judge(geometry: TranscriptGeometry): Boolean {
        judgedOnAGuess = !geometry.measuredToTheEnd
        val reachedTop = state.scrolled(offset = geometry.offset, contentHeight = geometry.contentHeight, viewportHeight = geometry.viewportHeight)
        publish()
        return reachedTop
    }

    private fun pin(animated: Boolean): List<Action> {
        if (reading && !animated) return emptyList()
        pinning = pinAttempts
        return listOf(Action.ScrollToBottom(animated))
    }

    private fun publish() {
        if (following != state.following) following = state.following
        if (missed != state.missed) missed = state.missed
    }

    companion object {
        /** How many times one trip to the tail may be repeated. */
        const val pinAttempts = 8
    }
}

/** The rows a transcript draws, worked out once per transcript version and detail level rather than on every pass through the view. */
class TranscriptSelectionCache {
    private var key: Pair<Int, TimelineDetail>? = null
    private var value: TranscriptSelection? = null

    fun selection(timeline: Timeline, detail: TimelineDetail): TranscriptSelection {
        val now = timeline.version to detail
        value?.let { if (key == now) return it }
        return TranscriptSelection(timeline, detail).also {
            value = it
            key = now
        }
    }
}
