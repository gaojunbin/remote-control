package com.junbingao.remotecontrol.core.state

import androidx.compose.runtime.mutableIntStateOf
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.QueuedMessage
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.TodoItem
import java.time.Instant
import java.util.Objects

/**
 * The ordered transcript of one session, plus the snapshots that ride along with it (todos,
 * queue). The store owns one and the checks drive it directly.
 *
 * RCCore's is a value type changed in place. This one is changed in place too — a transcript is
 * thousands of rows and every streaming delta is a change — and is observable itself: every read
 * goes through [version], which is snapshot state bumped by every mutation, so a screen that reads
 * any of it is redrawn when any of it changes, wherever the timeline is held. [copy] is the value
 * RCCore's assignment makes, and two timelines are equal when everything they hold is.
 */
class Timeline {
    private val list = ArrayList<TimelineEntry>()
    private var cursor = 0
    private var todoItems: List<TodoItem> = emptyList()
    private var queued: List<QueuedMessage> = emptyList()
    private var moreHistory = true
    private var loadedHistory = false
    private var gap = false
    private val sent = ArrayList<OptimisticMessage>()
    private val versionState = mutableIntStateOf(0)

    private val index = HashMap<String, Int>()

    /** Children keyed by parent block, so a tool row does not scan the whole transcript on every delta. */
    private val childIndex = HashMap<String, MutableList<String>>()

    /**
     * The seq each snapshot came from. A snapshot found while paging history is older than the live
     * one and must not overwrite it (amendment A6).
     */
    private var todosSeq = 0
    private var queueSeq = 0

    /**
     * Bumped by every queue snapshot applied and by a reset, so a removal the device refused can
     * tell whether the device has spoken about the queue since the row was taken off the screen.
     */
    private var queueGeneration = 0

    /**
     * Bumped by every mutation. A reader that built a list from this transcript keeps it until this
     * moves, rather than filtering the whole array again on every render pass.
     */
    val version: Int get() = versionState.intValue

    /** The rows, as they stand: a reader that keeps them past the next change takes a copy. */
    val entries: List<TimelineEntry> get() = observed(list)

    /** Highest seq applied from the live stream. Late or duplicate frames at or below this are dropped. */
    val lastSeq: Int get() = observed(cursor)
    val todos: List<TodoItem> get() = observed(todoItems)
    val queue: List<QueuedMessage> get() = observed(queued)
    val hasMoreHistory: Boolean get() = observed(moreHistory)
    val historyLoaded: Boolean get() = observed(loadedHistory)

    /**
     * True when a `seq` was skipped, so the transcript is missing an event. The gateway drops frames
     * over 64 KiB and a stream buffer can overrun, so a hole is part of the design and has to be
     * noticed and repaired.
     */
    val hasGap: Boolean get() = observed(gap)

    /**
     * Amendment A12: sent, not yet echoed by the device. Kept apart from [entries] because these
     * rows carry no `seq`: they must not move the replay cursor, must not be a history boundary, and
     * always sort last.
     */
    val optimistic: List<OptimisticMessage> get() = observed(sent)

    /** Reads go through the version, so whoever reads the transcript is told when it moves. */
    private fun <T> observed(value: T): T {
        versionState.intValue
        return value
    }

    /** The cursor for `session.history`: the oldest real event seq held, not a block's position, so paging cannot skip an event. */
    val oldestSeq: Int? get() = observed(list).minOfOrNull { it.eventSeq }

    /**
     * Top-level rows, then the sends the device has not confirmed yet. A pending row carries the
     * `user_message` it is about to become, so the transcript renders it like any other (amendment
     * A12).
     */
    val roots: List<TimelineEntry> get() = roots(TimelineDetail.detailed)

    /**
     * The top-level rows one detail level draws.
     *
     * A nested row belongs to the tool call above it. At Simple that tool call is not drawn, so its
     * sub-agent's work goes with it — except a card waiting on an answer, which comes up to the top
     * level rather than being hidden along with the row that would have held it.
     */
    fun roots(at: TimelineDetail): List<TimelineEntry> {
        val rows = observed(list).filterTo(ArrayList()) { entry ->
            if (!entry.isDrawn(at = at)) return@filterTo false
            if (!entry.isNested) return@filterTo true
            at == TimelineDetail.simple && entry.needsReply
        }
        sent.filter { index[it.id] == null }.mapTo(rows) { TimelineEntry(pending = it) }
        return rows
    }

    fun children(of: String): List<TimelineEntry> = children(of = of, at = TimelineDetail.detailed)

    /** What hangs under one tool call. Simple draws no tool call, so nothing hangs under anything there. */
    fun children(of: String, at: TimelineDetail): List<TimelineEntry> {
        if (at != TimelineDetail.detailed) return emptyList()
        return (observed(childIndex)[of] ?: emptyList()).mapNotNull { entry(id = it) }.filter { it.isRenderable }
    }

    fun entry(id: String): TimelineEntry? = observed(index[id])?.let { list[it] }

    /** The row an event landed in, for a caller that holds the event rather than the id it was keyed under. */
    fun entry(event: SessionEvent): TimelineEntry? = entry(id = key(event))

    /** The newest approval or question still waiting on the user. */
    val pendingRequest: TimelineEntry?
        get() = observed(list).lastOrNull { entry ->
            entry.approval?.status?.isActionable == true || entry.question?.status?.isActionable == true
        }

    /**
     * Apply one live event. Returns false when the event was dropped as stale.
     *
     * A seq that skips ahead means an event never arrived. The entry is still applied, but the
     * timeline is marked so the store can refill from the gateway rather than render a transcript
     * that is quietly missing a step.
     */
    fun apply(event: SessionEvent): Boolean {
        if (event.seq <= cursor) return false
        if (cursor > 0 && event.seq > cursor + 1) gap = true
        cursor = event.seq
        reconcileOptimistic(event)
        absorb(event)
        trimToLimit()
        touch()
        return true
    }

    /** Called once the store has refilled from `session.subscribe` or history. */
    fun clearGap() {
        if (!gap) return
        gap = false
        touch()
    }

    /** Adopt a cursor recovered from the offline cache, so a warm open can subscribe with `since_seq` instead of throwing the transcript away. */
    fun adoptCursor(seq: Int) {
        if (seq <= cursor) return
        cursor = seq
        touch()
    }

    /** Merge one page of older history. History never advances [lastSeq], and an entry already held from the live stream wins over an older copy. */
    fun prependHistory(events: List<SessionEvent>, hasMore: Boolean) {
        for (event in events.sortedBy { it.seq }) {
            reconcileOptimistic(event, isHistory = true)
            val key = key(event)
            val position = index[key]
            if (position != null) {
                if (event.seq <= list[position].latestSeq) continue
                list[position] = list[position].merge(event)
            } else {
                index[key] = list.size
                list.add(TimelineEntry(event = event, id = key))
            }
            absorbSnapshot(event)
        }
        sortEntries()
        moreHistory = hasMore
        loadedHistory = true
        touch()
    }

    /**
     * Replace the whole transcript, used when the gateway reports `resync`.
     *
     * The unconfirmed sends survive it: a message the user just typed must not vanish because the
     * socket came back and the gateway asked for a reload. They are reconciled again as the reloaded
     * events come through.
     */
    fun reset() {
        list.clear()
        index.clear()
        childIndex.clear()
        gap = false
        todoItems = emptyList()
        queued = emptyList()
        cursor = 0
        todosSeq = 0
        queueSeq = 0
        queueGeneration += 1
        moreHistory = true
        loadedHistory = false
        touch()
    }

    /** The queue snapshot from a `session.subscribe` reply, which has no seq of its own: it describes the session as of the current cursor. */
    fun applySubscribedQueue(pending: List<QueuedMessage>) {
        if (cursor < queueSeq) return
        queued = pending
        queueSeq = cursor
        queueGeneration += 1
        dropQueuedOptimistic()
        touch()
    }

    /** A queued message taken off the screen before the device has let go of it: what it was, where it stood, and which snapshot it was taken from. */
    @ConsistentCopyVisibility
    data class QueueRemoval internal constructor(
        val message: QueuedMessage,
        internal val position: Int,
        internal val generation: Int,
    )

    /**
     * A queued message the person removed. The device's next snapshot says the same thing and
     * replaces this one as any snapshot does; the list is not made to wait for it, because a swipe
     * has already taken the row off the screen. Null when the list does not hold it.
     */
    fun dropQueued(id: String): QueueRemoval? {
        val position = queued.indexOfFirst { it.id == id }
        if (position < 0) return null
        val removal = QueueRemoval(message = queued[position], position = position, generation = queueGeneration)
        queued = queued.filterIndexed { at, _ -> at != position }
        touch()
        return removal
    }

    /**
     * The device refused a removal, so it still holds the message, and the row goes back where it
     * stood — unless a snapshot arrived meanwhile, which says better than this where everything is.
     */
    fun restoreQueued(removal: QueueRemoval) {
        if (removal.generation != queueGeneration || queued.any { it.id == removal.message.id }) return
        queued = queued.toMutableList().apply { add(minOf(removal.position, size), removal.message) }
        touch()
    }

    // Amendment A12: sends the device has not confirmed

    /** Show a message the moment it is sent. Idempotent, so a Retry under the same request id reuses the row rather than adding a second one. */
    fun addOptimistic(message: OptimisticMessage) {
        if (index[message.id] != null || sent.any { it.id == message.id }) return
        sent.add(message)
        touch()
    }

    /** Take a pending row away: the send was refused, or the queue owns it now. */
    fun removeOptimistic(id: String) {
        sent.removeAll { it.id == id }
        touch()
    }

    /**
     * Amendment A14: the device steered the message into the running turn. The row stays where it
     * is — the foot of the transcript, which is where the device's block will land once the agent
     * reads it — and stops counting down towards "unconfirmed", because there is nothing left to
     * confirm.
     */
    fun markSteered(id: String) {
        val position = sent.indexOfFirst { it.id == id }
        if (position < 0) return
        sent[position] = sent[position].copy(isSteering = true)
        touch()
    }

    /** The pending rows that have waited too long to still claim they are on their way. Pure; the caller supplies the clock. */
    fun unconfirmedOptimistic(at: Instant = Instant.now()): List<OptimisticMessage> =
        observed(sent).filter { it.isUnconfirmed(at = at) }

    /**
     * A queued message is represented by the queue row above the composer until the device dequeues
     * it and emits the `user_message` under the same id, so showing both would show it twice.
     */
    private fun dropQueuedOptimistic() {
        if (sent.isEmpty() || queued.isEmpty()) return
        val ids = queued.mapTo(HashSet()) { it.id }
        sent.removeAll { it.id in ids }
    }

    /**
     * Retire the pending row an incoming event confirms. Matching on the block id is amendment A12;
     * matching one row by text is the fallback for a device that still mints its own ids, and never
     * retires more than one.
     *
     * The fallback is for events that arrive after the send, which is every live event and no page
     * of history. Amendment A14 keeps a steered row on screen for the rest of the turn, and an older
     * message repeating the same words — "continue", sent twice in one session — would otherwise
     * take it away the moment the reader scrolled up.
     */
    private fun reconcileOptimistic(event: SessionEvent, isHistory: Boolean = false) {
        if (sent.isEmpty()) return
        val key = key(event)
        if (sent.any { it.id == key }) {
            sent.removeAll { it.id == key }
            return
        }
        val body = event.body
        if (isHistory || body !is SessionEventBody.UserMessage || body.payload.source != EventSource.remote) return
        val position = sent.indexOfFirst { it.text == body.payload.text }
        if (position >= 0) sent.removeAt(position)
    }

    /**
     * Fold in the untruncated version of one block from `session.block`.
     *
     * The reply carries the seq of the version it holds, so a block that has streamed on since the
     * request left answers below the row's newest seq and must not be applied: "Open full output"
     * would write the older body over the newer one. An answer at the same seq is the untruncated
     * copy of what is on screen, which is the whole point of the request.
     */
    fun replaceBlock(with: SessionEvent) {
        reconcileOptimistic(with)
        val position = index[key(with)]
        if (position == null) {
            absorb(with)
            touch()
            return
        }
        if (with.seq < list[position].latestSeq) return
        val before = list[position].seq
        list[position] = list[position].merge(with)
        // `merge` takes the earliest position it has been told about, and the reply is the first
        // event to carry `first_seq` for a block whose own events did not. A row that moves has to
        // be put back in order.
        if (list[position].seq != before) sortEntries()
        touch()
    }

    fun markHistoryExhausted() {
        moreHistory = false
        loadedHistory = true
        touch()
    }

    /** An independent copy, as assigning RCCore's value type makes one. */
    fun copy(): Timeline {
        val copy = Timeline()
        copy.list.addAll(list)
        copy.cursor = cursor
        copy.todoItems = todoItems
        copy.queued = queued
        copy.moreHistory = moreHistory
        copy.loadedHistory = loadedHistory
        copy.gap = gap
        copy.sent.addAll(sent)
        copy.versionState.intValue = versionState.intValue
        copy.index.putAll(index)
        for ((parent, children) in childIndex) copy.childIndex[parent] = children.toMutableList()
        copy.todosSeq = todosSeq
        copy.queueSeq = queueSeq
        copy.queueGeneration = queueGeneration
        return copy
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Timeline) return false
        return list == other.list && cursor == other.cursor && todoItems == other.todoItems &&
            queued == other.queued && moreHistory == other.moreHistory && loadedHistory == other.loadedHistory &&
            gap == other.gap && sent == other.sent && versionState.intValue == other.versionState.intValue &&
            index == other.index && childIndex == other.childIndex && todosSeq == other.todosSeq &&
            queueSeq == other.queueSeq && queueGeneration == other.queueGeneration
    }

    override fun hashCode(): Int = Objects.hash(list, cursor, todoItems, queued, sent, versionState.intValue)

    override fun toString(): String = "Timeline(entries=${list.size}, lastSeq=$cursor, version=${versionState.intValue})"

    /** Record that the rows this transcript draws have changed. */
    private fun touch() {
        versionState.intValue += 1
    }

    /**
     * Hold the transcript to [entryLimit] rows, oldest first.
     *
     * Only the live path trims. A page of history is older than everything held, so trimming after
     * one would throw away exactly what the reader scrolled up to see and page for it again on the
     * next scroll.
     */
    private fun trimToLimit() {
        if (list.size <= entryLimit) return
        list.subList(0, list.size - entryLimit).clear()
        // The rows are still on the gateway, so scrolling back reaches them.
        moreHistory = true
        reindex()
    }

    private fun absorb(event: SessionEvent) {
        absorbSnapshot(event)
        when (event.body) {
            is SessionEventBody.Todos, is SessionEventBody.Status, is SessionEventBody.Meta,
            is SessionEventBody.Queue -> return
            else -> Unit
        }
        val key = key(event)
        val position = index[key]
        if (position != null) {
            val before = list[position].seq
            list[position] = list[position].merge(event)
            if (list[position].seq != before) sortEntries()
        } else {
            val entry = TimelineEntry(event = event, id = key)
            // The common case is an append at the end; only an out-of-order position pays for a sort.
            val inOrder = list.lastOrNull()?.let { it.seq <= entry.seq } ?: true
            index[key] = list.size
            list.add(entry)
            event.parentBlockID?.let { childIndex.getOrPut(it) { mutableListOf() }.add(key) }
            if (!inOrder) sortEntries()
        }
    }

    private fun sortEntries() {
        list.sortWith(compareBy<TimelineEntry> { it.seq }.thenBy { it.eventSeq })
        reindex()
    }

    /**
     * Snapshots replace the previous list, but only when they are at least as new as the one already
     * held. Replayed events, history pages and live frames all pass through here, so the newest wins
     * regardless of arrival order (amendment A6).
     */
    private fun absorbSnapshot(event: SessionEvent) {
        when (val body = event.body) {
            is SessionEventBody.Todos -> {
                if (event.seq < todosSeq) return
                todoItems = body.payload.items
                todosSeq = event.seq
            }
            is SessionEventBody.Queue -> {
                if (event.seq < queueSeq) return
                queued = body.payload.pending
                queueSeq = event.seq
                queueGeneration += 1
                dropQueuedOptimistic()
            }
            else -> Unit
        }
    }

    private fun reindex() {
        index.clear()
        childIndex.clear()
        for ((position, entry) in list.withIndex()) {
            index[entry.id] = position
            entry.parentID?.let { childIndex.getOrPut(it) { mutableListOf() }.add(entry.id) }
        }
    }

    private fun key(event: SessionEvent): String = event.blockID ?: "seq:${event.seq}"

    companion object {
        /**
         * The most rows one open transcript holds. A session left open through a day of agent work
         * would otherwise grow without limit. Dropping the oldest rows is safe because
         * [hasMoreHistory] goes back to true with them: scrolling up pages them from the gateway
         * again.
         */
        const val entryLimit = 3000
    }
}
