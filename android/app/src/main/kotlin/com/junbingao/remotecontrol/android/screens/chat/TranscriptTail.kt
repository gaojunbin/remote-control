package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.ScrollTail
import java.util.Objects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The transcript's reading position: who is moving it, whether it is at the foot, and the way back
 * down. `ScrollTail` in the core is the whole rule; this reads the list for it.
 *
 * The reader always wins: while a finger or a fling moves the transcript the numbers are theirs,
 * whatever the content did at the same moment — rows settling after a turn, history being laid
 * out lazily — and nothing scrolls under them.
 */
internal class TranscriptTail(
    private val chat: ChatStore,
    private val list: LazyListState,
    private val scope: CoroutineScope,
    /** How close to the foot still counts as the foot, in pixels. */
    private val threshold: Float,
    /** The composer under the transcript, as tall as its last layout made it. */
    private val composer: ComposerFrame,
) {
    /** Called when a finger starts dragging the transcript. */
    var onDragStart: () -> Unit = {}

    /**
     * Until when the geometry the list reports belongs to a scroll this view started rather than to
     * the reader. A deadline rather than a flag, so a scroll that lands a point short can never pin
     * the button away for good.
     */
    private var settlesAt = 0L
    private var dragging = false
    private var scrolling = false

    /** Content arrived while the reader was scrolling at the foot; the catch-up waits until their finger is off the transcript. */
    private var catchUpWhenStill = false

    /** The jump to the tail that is on its way, so the next one replaces it and a finger on the transcript ends it. */
    private var jump: Job? = null

    /** The numbers the list reported last. */
    private var geometry = TailGeometry.unmeasured

    /** Whether the transcript has been put at its newest message once: a conversation opens there. */
    private var placed = false

    /** The composer's height at the last geometry change, to tell its resizes from everything else's. */
    private var composerHeight = 0

    /** Who is moving the transcript right now, for `ScrollTail.decide`. */
    private val motion: ScrollTail.ReaderMotion
        get() = when {
            dragging -> ScrollTail.ReaderMotion.reading
            scrolling -> ScrollTail.ReaderMotion.animating
            list.isScrollInProgress -> ScrollTail.ReaderMotion.reading
            System.nanoTime() < settlesAt -> ScrollTail.ReaderMotion.animating
            else -> ScrollTail.ReaderMotion.still
        }

    suspend fun watch() = coroutineScope {
        launch {
            snapshotFlow { TailGeometry.of(list.layoutInfo, threshold) }.collect { current -> receive(current) }
        }
        launch {
            list.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> {
                        dragging = true
                        // A hand on the transcript ends a jump: where the reader takes it is where
                        // they want to be.
                        endJump()
                        onDragStart()
                    }
                    is DragInteraction.Stop, is DragInteraction.Cancel -> dragging = false
                }
            }
        }
        launch {
            // The finger has left the transcript and the fling has run out: whatever arrived while
            // it was there is caught up on now, if the reader stayed at the foot.
            snapshotFlow { list.isScrollInProgress }.collect { moving ->
                if (moving || !catchUpWhenStill) return@collect
                catchUpWhenStill = false
                if (chat.isFollowingTail) scrollToTail()
            }
        }
    }

    private fun receive(current: TailGeometry) {
        val previous = geometry
        geometry = current
        if (!placed) {
            if (current.items <= 1) return
            // A conversation opens at its newest message, cached or streamed: `defaultScrollAnchor`
            // for the initial offset, which is a placement rather than a scroll this view animates,
            // so nothing that arrives next is taken for the end of one.
            placed = true
            composerHeight = composer.height
            if (chat.isFollowingTail && !current.isAtBottom) scope.launch { list.scrollToItem(tailIndex()) }
            return
        }
        // The composer growing under the transcript — a longer draft, dictation, the `/` card, the
        // keyboard — never scrolls it on the iPhone: that resize is animated, and geometry that
        // changes under an animation is the view's own, which can only confirm the tail is still in
        // view. The rows keep their place and the next content brings the reader down. A notice
        // above the transcript or the status line under it is not the composer, and a reader at
        // the foot is kept there.
        val resized = previous.items > 0 && current.viewport != previous.viewport && composer.height != composerHeight
        composerHeight = composer.height
        when (val action = ScrollTail.decide(
            rangeChanged = current.range != previous.range,
            atBottom = current.isAtBottom,
            following = chat.isFollowingTail,
            motion = if (resized) ScrollTail.ReaderMotion.animating else motion,
        )) {
            ScrollTail.TailAction.None -> Unit
            is ScrollTail.TailAction.Follow -> chat.isFollowingTail = action.following
            ScrollTail.TailAction.ScrollToTail -> scrollToTail()
        }
    }

    /** New content while following: scroll to it, unless the reader's finger is on the transcript, in which case it waits for the finger to lift. */
    fun followNewContent() {
        if (!chat.isFollowingTail || !placed) return
        if (motion == ScrollTail.ReaderMotion.reading) catchUpWhenStill = true else scrollToTail()
    }

    /**
     * To the very end of the transcript, however far away it is, and following again only once the
     * list says the tail is on screen — never on the strength of having asked. Each scroll measures
     * the rows it passes, so one usually gets there; the next starts from where it landed, up to
     * `ScrollTail.jumpLimit` of them, so a transcript growing faster than it is scrolled cannot
     * hold the view for ever.
     */
    fun scrollToTail() {
        jump?.cancel()
        jump = scope.launch {
            for (attempt in 1..ScrollTail.jumpLimit) {
                settlesAt = System.nanoTime() + SETTLE_WINDOW_NANOS
                scrolling = true
                try {
                    lineUpTail()
                } finally {
                    scrolling = false
                }
                val step = ScrollTail.jump(attempt = attempt, atBottom = TailGeometry.of(list.layoutInfo, threshold).isAtBottom)
                if (step != ScrollTail.JumpStep.again) break
            }
            if (TailGeometry.of(list.layoutInfo, threshold).isAtBottom) chat.isFollowingTail = true
        }
    }

    /**
     * `scrollTo(tail, anchor: .bottom)`: the tail's foot on the viewport's, so the padding under it
     * stays out of view as it does on the iPhone. A tail pages away is brought on screen first.
     */
    private suspend fun lineUpTail() {
        val index = tailIndex()
        if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.animateScrollToItem(index)
        val info = list.layoutInfo
        val tail = info.visibleItemsInfo.lastOrNull { it.index == index } ?: return
        val delta = (tail.offset + tail.size - info.viewportEndOffset).toFloat()
        if (delta != 0f) list.animateScrollBy(delta)
    }

    fun endJump() {
        jump?.cancel()
        jump = null
        scrolling = false
    }

    /** After a page of history went in above, the row the reader was looking at stays at the top. */
    suspend fun keepInView(rowID: String?) {
        rowID ?: return
        val index = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == rowID }?.index
            ?: findIndex(rowID)
            ?: return
        list.scrollToItem(index)
    }

    private fun findIndex(rowID: String): Int? {
        // The rows' keys are their ids, laid out after the load button.
        val drawn = chat.rows.filterNot(TimelineRows::drawsNothing)
        val position = drawn.indexOfFirst { it.id == rowID }
        if (position < 0) return null
        return position + if (chat.timeline.hasMoreHistory) 1 else 0
    }

    private fun tailIndex(): Int = (list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)

    companion object {
        const val tailKey = "chat.tail"
        const val loadOlderKey = "chat.loadOlder"

        /**
         * How long the geometry a scroll produces goes on being this view's rather than the
         * reader's, because a scroll it starts is not reported as under way at once.
         */
        private const val SETTLE_WINDOW_NANOS = 450_000_000L
    }
}

/** What the tail rule needs from a laid-out list, compared between passes. */
internal data class TailGeometry(val isAtBottom: Boolean, val range: Int, val items: Int, val viewport: Int) {
    companion object {
        /** Before the list has said anything: nothing to scroll, so it reads as the bottom. */
        val unmeasured = TailGeometry(isAtBottom = true, range = 0, items = 0, viewport = 0)

        /**
         * At the bottom when the last row ends within [threshold] of the foot of the viewport. The
         * range stands for how far the content could scroll: it moves when rows arrive or change
         * size and when the viewport is resized — the keyboard opening — and with the reader's own
         * scrolling, which the rule reads as theirs.
         */
        fun of(info: LazyListLayoutInfo, threshold: Float): TailGeometry {
            val total = info.totalItemsCount
            val visible = info.visibleItemsInfo
            val last = visible.lastOrNull()
            val atBottom = when {
                total == 0 || last == null -> true
                last.index < total - 1 -> false
                else -> last.offset + last.size + info.afterContentPadding - info.viewportEndOffset <= threshold
            }
            val range = Objects.hash(total, info.viewportSize.height, visible.map { it.key to it.size })
            return TailGeometry(isAtBottom = atBottom, range = range, items = total, viewport = info.viewportSize.height)
        }
    }
}

/** How tall the composer under the transcript is, written as it is laid out and read when the transcript's geometry changes. */
internal class ComposerFrame {
    var height = 0
}
