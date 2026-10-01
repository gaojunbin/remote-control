package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AttachmentInfo
import com.junbingao.remotecontrol.core.protocol.QueuedMessage

/**
 * What a demo device holds behind a session's turn, in the order it will deliver it.
 *
 * Amendment A43: the queue is always in `ts` order, and no two entries share a `ts`. A message
 * sent without `queue_ts` joins the end under the current time, or one past the last entry's `ts`
 * when that is later. A message sent back with `queue_ts` — a queued message taken out to be
 * edited — goes back under that `ts`, in front of the first entry with a greater one, so it waits
 * in the place it left however the line moved meanwhile.
 *
 * RCCore's is a value type changed in place by its owner; this one is a class the gateway owns
 * alone, so the two read the same.
 */
internal class DemoQueue(items: List<Item> = emptyList()) {
    /**
     * One held message: what the snapshot says about it, and the files it carries, which stay on
     * the device until the message is delivered.
     */
    data class Item(val message: QueuedMessage, val files: List<AttachmentInfo>) {
        constructor(id: String, text: String, ts: Long, files: List<AttachmentInfo> = emptyList()) : this(
            message = QueuedMessage(id = id, text = text, ts = ts, attachments = if (files.isEmpty()) null else files.size),
            files = files,
        )

        /** The same message under another `ts`. */
        fun stamped(ts: Long): Item = Item(id = message.id, text = message.text, ts = ts, files = files)
    }

    var items: List<Item> = items
        private set

    /**
     * The `queue` snapshot: every held message in delivery order, with the number of files each
     * carries and never the files themselves.
     */
    val pending: List<QueuedMessage> get() = items.map { it.message }

    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * A message sent without `queue_ts`, stamped with the time it arrived: the end of the line,
     * under a `ts` of its own.
     */
    fun append(item: Item) {
        val after = items.lastOrNull()?.let { it.message.ts + 1 } ?: item.message.ts
        items = items + item.stamped(maxOf(item.message.ts, after))
    }

    /**
     * A message sent with `queue_ts`: back under that `ts`, after any entry with the same one and
     * before the first with a greater one.
     */
    fun insert(item: Item) {
        val later = items.indexOfFirst { it.message.ts > item.message.ts }
        val place = if (later < 0) items.size else later
        items = items.toMutableList().apply { add(place, item) }
    }

    /**
     * `session.queue_remove`: null when nothing by that id is held — delivered already, or never
     * queued — which the device answers with `not_found`.
     */
    fun remove(id: String): Item? {
        val index = items.indexOfFirst { it.message.id == id }
        if (index < 0) return null
        val item = items[index]
        items = items.toMutableList().apply { removeAt(index) }
        return item
    }

    /** The next message to deliver, taken out of the line. */
    fun next(): Item? {
        val first = items.firstOrNull() ?: return null
        items = items.drop(1)
        return first
    }
}
