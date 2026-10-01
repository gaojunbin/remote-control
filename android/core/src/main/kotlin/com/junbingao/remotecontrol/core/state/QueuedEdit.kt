package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.QueuedMessage

/**
 * Amendment A43: a queued message taken out of the line to be edited in the composer, and what the
 * field held when that happened.
 *
 * `docs/DESIGN.md` § "The composer" → **Up next**: the device removes the entry first, so it cannot
 * deliver words that are still changing; Send puts the edited words back under the entry's own
 * `ts`, and Cancel puts the original ones back the same way. Whatever the field held when the edit
 * began is set aside rather than overwritten, and comes back once the message is back in the line.
 * The composer's attachments are the view's, so it keeps those aside itself; the words are kept
 * here, with the store.
 */
data class QueuedEdit(
    /** The `ts` the entry had in the snapshot. Sent back as `queue_ts`, it holds the message in the place it left however the queue moved since. */
    val ts: Long,
    /** The words as they were queued, which Cancel puts back. */
    val original: String,
    /** What the field held when the edit began. */
    val aside: String,
) {
    companion object {
        operator fun invoke(entry: QueuedMessage, aside: String): QueuedEdit =
            QueuedEdit(ts = entry.ts, original = entry.text, aside = aside)
    }
}
