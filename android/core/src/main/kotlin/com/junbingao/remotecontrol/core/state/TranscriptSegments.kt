package com.junbingao.remotecontrol.core.state

/**
 * The transcript of a dictation that outlived a single recognition request.
 *
 * Neither speech backend can listen forever. A platform recogniser ends a server-backed request
 * after roughly a minute, and the gateway accepts at most 120 s of audio per utterance. Both
 * therefore roll over to a fresh request while the microphone keeps running, and each request owns
 * one slot here.
 *
 * Slots are filled out of order: a new segment's first partial can arrive before the previous
 * segment's final does. The text is therefore joined by position rather than by arrival, and a
 * segment stays *open* until its backend has said the last word about it, so a dictation is only
 * finished when every slot has settled.
 *
 * A slot is not one sentence. A recognizer may start its transcription over inside one request —
 * the speaker paused, and what comes back next is a new sentence rather than a longer version of the
 * old one. The slot then keeps what it had as a settled sub-segment and carries on in a new one, so
 * no word spoken before the pause is dropped. [isFreshStart] is the rule that tells that apart from
 * the recognizer revising its own last few words.
 *
 * RCCore's is a value type changed in place; this one is changed in place, and [copy] is the value
 * an assignment makes.
 */
class TranscriptSegments {
    /** What one recognition request has heard: the sub-segments it is finished with, and the text it may still revise. */
    private data class Slot(val settled: List<String> = emptyList(), val live: String = "") {
        val text: String get() = (settled + live).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private val slots = mutableListOf<Slot>()
    private val open = mutableSetOf<Int>()

    /** Open a slot for a new recognition request and return its index. */
    fun begin(): Int {
        val index = slots.size
        slots.add(Slot())
        open.add(index)
        return index
    }

    /** The slot currently receiving audio, which is the last one opened. */
    val active: Int? get() = if (slots.isEmpty()) null else slots.size - 1

    val count: Int get() = slots.size

    /**
     * Take one result for a segment, and say whether the joined transcript moved.
     *
     * Every backend reports the whole of what it is transcribing on each result rather than a delta,
     * so this normally replaces the segment's live text. When the result is a fresh start instead,
     * the live text is settled first and the result opens the next sub-segment. A result for a slot
     * that was never opened is ignored rather than trusted.
     */
    fun update(index: Int, text: String): Boolean {
        if (index !in slots.indices) return false
        val trimmed = text.trimmed
        val slot = slots[index]
        if (slot.live == trimmed) return false
        val settled = if (isFreshStart(after = slot.live, next = trimmed)) slot.settled + slot.live else slot.settled
        slots[index] = Slot(settled = settled, live = trimmed)
        return true
    }

    /** Mark a segment finished. Its backend will say nothing more about it. */
    fun end(index: Int) {
        open.remove(index)
    }

    /** True once every segment has finished, which is the only moment a transcript can be called final. */
    val isSettled: Boolean get() = open.isEmpty()

    /** Whether this segment's backend may still say something about it. */
    fun isOpen(index: Int): Boolean = index in open

    fun text(at: Int): String? = slots.getOrNull(at)?.text

    /** Every segment in the order its audio was spoken, empty ones dropped. */
    val joined: String get() = slots.map { it.text }.filter { it.isNotEmpty() }.joinToString(" ")

    /** An independent copy, as assigning RCCore's value type makes one. */
    fun copy(): TranscriptSegments = TranscriptSegments().also {
        it.slots.addAll(slots)
        it.open.addAll(open)
    }

    override fun equals(other: Any?): Boolean = other is TranscriptSegments && slots == other.slots && open == other.open

    override fun hashCode(): Int = 31 * slots.hashCode() + open.hashCode()

    override fun toString(): String = "TranscriptSegments(joined=$joined, open=$open)"

    companion object {
        /**
         * How much a slot must already hold before a short, unrelated partial is read as a new
         * sentence. Below it the recognizer is still finding the first few words, where a rewrite is
         * ordinary and a commit would double them up.
         */
        private const val restartFloor = 12

        /**
         * Whether a result starts the sentence over rather than carrying the same one on.
         *
         * Three things have to hold at once, and together they leave the recognizer's own revisions
         * alone:
         *
         * 1. The segment already holds a sentence's worth of speech, so the first few words of an
         *    utterance are never settled behind a rewrite.
         * 2. The result is shorter than what the segment holds. A recognizer extending or revising
         *    an utterance keeps roughly what it had; the first partial after a restart is a word or
         *    two.
         * 3. The two share no real beginning: the run of characters they agree on, ignoring case,
         *    spacing and punctuation, covers less than half the new result. A rewrite of the last few
         *    words agrees on almost all of it.
         */
        internal fun isFreshStart(after: String, next: String): Boolean {
            val settled = normalized(after)
            val arriving = normalized(next)
            if (settled.size < restartFloor || arriving.isEmpty()) return false
            if (arriving.size >= settled.size) return false
            return commonPrefixLength(settled, arriving) * 2 < arriving.size
        }

        /**
         * The comparable shape of a transcript: letters and digits, folded to lower case. Spacing and
         * punctuation move around between partials and say nothing about whether this is the same
         * sentence.
         */
        private fun normalized(text: String): List<String> =
            text.lowercase().characters().filter { isLetter(it) || isNumber(it) }

        private fun commonPrefixLength(first: List<String>, second: List<String>): Int {
            var length = 0
            while (length < first.size && length < second.size && first[length] == second[length]) length += 1
            return length
        }
    }
}
