package com.junbingao.remotecontrol.win.chat.timeline

import com.junbingao.remotecontrol.core.state.TimelineDetail

/**
 * `web/src/features/chat/useScrollFollow.ts`: the transcript follows the tail while the reader has
 * not scrolled away, counts the new blocks they missed otherwise, and keeps their rows where they
 * were when an older history page is prepended (`docs/DESIGN.md` § "Reading position").
 *
 * Pure arithmetic over what the scroll view reports and what the rows are, so the rules are
 * checked without a window. The view applies what it answers. Lengths are in whatever unit the
 * view measures in.
 */
class ScrollFollow(private var rows: Rows) {
    /** What the rows are, as the web's hook reads them on every render. */
    data class Rows(
        /** Changes on every event, streaming and unconfirmed sends included. */
        val revision: String,
        /**
         * Changes when the same blocks are drawn differently — the detail level. A redraw is not
         * new content: it counts nothing and moves no anchor.
         */
        val redraw: TimelineDetail,
        val firstKey: String?,
        val lastKey: String?,
    )

    var following = true
        private set

    /** Blocks that landed while the reader was away, not streaming deltas. */
    var missed = 0
        private set

    /** The content height a prepended page is to be measured against, until the scroll view reports the height it has with the page in it. */
    var anchorHeight: Float? = null
        private set

    private var contentHeight = 0f

    /**
     * `onScroll`: the reader, or a scroll of this view's, moved the offset. Answers whether the top
     * is close enough to ask for older history.
     */
    fun scrolled(offset: Float, contentHeight: Float, viewportHeight: Float): Boolean {
        val distance = contentHeight - offset - viewportHeight
        following = distance <= bottomThreshold
        if (following) missed = 0
        return offset <= topTrigger
    }

    /**
     * The rows changed. The web runs three effects, in this order: the anchor of a prepended page,
     * the follow, and the count. Answers whether the view is to scroll to the bottom.
     */
    fun rowsChanged(next: Rows): Boolean {
        val redrawn = next.redraw != rows.redraw
        // Only a prepended history page moves the anchor. Content appended below the viewport
        // must not drag the reader down, and neither must a redraw: the rows around the reader
        // changed because they asked for it.
        val prepended = next.firstKey != null && next.firstKey != rows.firstKey
        if (prepended && !redrawn && !following) anchorHeight = contentHeight

        val scrolls = following && (next.revision != rows.revision || redrawn)
        if (scrolls) missed = 0

        // Count blocks, not streaming deltas: "2 new" for two messages, not once per flush. A
        // redraw starts the count again.
        if (redrawn) {
            missed = 0
        } else if (next.lastKey != rows.lastKey && next.lastKey != null && !following) {
            missed += 1
        }
        rows = next
        return scrolls
    }

    /** The scroll view reported the content's height. Answers how far to move the offset down so the rows a prepended page pushed are back in place. */
    fun measured(contentHeight: Float): Float? {
        val previous = this.contentHeight
        this.contentHeight = contentHeight
        val anchor = anchorHeight ?: return null
        if (contentHeight == previous) return null
        anchorHeight = null
        val delta = contentHeight - anchor
        return if (delta > 0) delta else null
    }

    /** The view scrolled to the tail itself: following again, nothing missed. */
    fun reachedBottom() {
        following = true
        missed = 0
    }

    /** The jump-to-latest button was pressed: the count goes at once, and the button itself goes when the tail is on screen. */
    fun jumpStarted() {
        missed = 0
    }

    companion object {
        /** Within this of the foot still counts as at the bottom. */
        const val bottomThreshold = 56f

        /** Within this of the top asks for the previous page of history. */
        const val topTrigger = 120f
    }
}
