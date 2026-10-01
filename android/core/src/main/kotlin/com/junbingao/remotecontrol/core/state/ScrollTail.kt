package com.junbingao.remotecontrol.core.state

import kotlin.math.max

/**
 * Where the reader is in a scrolling transcript, worked out from the numbers a scroll view reports.
 * Pure arithmetic, so the rule that decides whether the timeline follows new content is checked
 * without a device.
 */
object ScrollTail {
    /**
     * How close to the foot of the content still counts as being at the bottom. A row that settles a
     * few points short of the end must not stop the timeline from following what arrives next.
     */
    const val threshold: Double = 40.0

    /**
     * True when the visible rectangle ends within [threshold] of the content. A transcript shorter
     * than its container has no bottom to leave, so it is always at the bottom.
     *
     * @param contentHeight the scrolled content, its insets included.
     * @param containerHeight the height of the window onto that content.
     * @param offset how far the content has been scrolled, measured the same way.
     */
    fun isAtBottom(contentHeight: Double, containerHeight: Double, offset: Double,
                   threshold: Double = ScrollTail.threshold): Boolean =
        offset >= max(0.0, contentHeight - containerHeight) - threshold

    /**
     * Who is moving the transcript when its geometry changes. A scroll view reports the same numbers
     * whether a finger dragged it, a row grew under it, or the view itself scrolled to the tail, and
     * the three mean different things.
     */
    enum class ReaderMotion {
        /** Nobody: rows arrived, the keyboard opened, a row settled. */
        still,

        /** The reader: a drag, a fling still decelerating. */
        reading,

        /** This view: a scroll it started is on its way. */
        animating,
    }

    /** What the timeline does about one geometry change. */
    sealed interface TailAction {
        data object None : TailAction

        /** Set whether the timeline follows new content. */
        data class Follow(val following: Boolean) : TailAction

        /** Scroll to the tail and keep following. */
        data object ScrollToTail : TailAction
    }

    /**
     * The rule for one geometry change. `rangeChanged` is whether the scrollable range moved — rows
     * arrived or the container was resized — and `atBottom` and `following` are as they stand when
     * it arrives.
     *
     * The reader always wins: while a finger is on the transcript, or a fling is still running, the
     * numbers are theirs, whatever the content did at the same moment, and nothing scrolls under
     * them. That is what keeps a transcript whose rows are still settling after a turn — or being
     * laid out lazily as history scrolls into view — from snapping back to the foot each time its
     * height moves while someone is scrolling up.
     */
    fun decide(rangeChanged: Boolean, atBottom: Boolean, following: Boolean, motion: ReaderMotion): TailAction =
        when (motion) {
            ReaderMotion.reading -> TailAction.Follow(atBottom)
            // A scroll this view started can only confirm that it arrived.
            ReaderMotion.animating -> if (atBottom) TailAction.Follow(true) else TailAction.None
            ReaderMotion.still -> when {
                !rangeChanged -> TailAction.Follow(atBottom)
                // Someone at the foot stays there; someone away is left where they are, unless the
                // range shrank until nothing scrolls any more.
                following -> TailAction.ScrollToTail
                atBottom -> TailAction.Follow(true)
                else -> TailAction.None
            }
        }

    /**
     * How a jump to the tail is going. One scroll is not the whole journey: a lazy list places the
     * end of its content where it guessed the rows it had not laid out would be, and laying them out
     * on the way there moves the end again, so a jump from pages away lands short of it.
     */
    enum class JumpStep {
        /** The tail is on screen. Follow new content again. */
        arrived,

        /** Scroll again, from closer than last time. */
        again,

        /**
         * Far enough. A transcript growing faster than it is scrolled must not hold the view for
         * ever; the reader is left where the last scroll put them, with the way back down still on
         * screen.
         */
        giveUp,
    }

    /** How many scrolls one jump may spend. Each starts from closer to the end than the last, so what is left falls fast and two are usually enough. */
    const val jumpLimit = 6

    /** What a jump does now that its `attempt`-th scroll has settled, counted from one. */
    fun jump(attempt: Int, atBottom: Boolean, limit: Int = jumpLimit): JumpStep {
        if (atBottom) return JumpStep.arrived
        return if (attempt < limit) JumpStep.again else JumpStep.giveUp
    }

    /**
     * What the jump-to-latest button counts, or null when nothing arrived while the reader was away.
     * Blocks, never streaming deltas, so a long answer is one update rather than two hundred.
     */
    fun badge(updates: Int): String? {
        if (updates <= 0) return null
        return if (updates > 99) "99+" else updates.toString()
    }

    /** The same count as a screen reader reads it, appended to the button's label. */
    fun spokenBadge(updates: Int): String? {
        if (updates <= 0) return null
        return "$updates new update${if (updates == 1) "" else "s"}"
    }
}
