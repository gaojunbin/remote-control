package com.junbingao.remotecontrol.win.voice

import com.junbingao.remotecontrol.core.state.trimmed

/**
 * `web/src/features/voice/segments.ts`: the transcript of a dictation that outlived a single
 * gateway request.
 *
 * The gateway accepts at most 120 s of audio per utterance, so a long dictation rolls over to a
 * fresh socket while the microphone keeps running, and each socket owns one slot here. Slots fill
 * out of order — a new segment's first partial can arrive before the previous segment's final — so
 * the text is joined by position rather than by arrival, and a segment stays open until its socket
 * has said the last word about it.
 *
 * The gateway reports the whole segment on every result, so a result replaces the slot's text; the
 * iPhone's `TranscriptSegments` also reads a recogniser that starts its sentence over, which the
 * web's socket never does. Changed in place, as the Mac's value is, by the one controller that owns
 * it.
 */
class DictationSegments {
    private val texts = mutableListOf<String>()
    private val open = mutableSetOf<Int>()

    /** Open a slot for a new socket and return its index. */
    fun begin(): Int {
        texts += ""
        open += texts.size - 1
        return texts.size - 1
    }

    /** The slot taking audio right now, which is the last one opened. */
    val active: Int? get() = if (texts.isEmpty()) null else texts.size - 1

    /**
     * Replace one segment's text and say whether the joined transcript moved. A result for a slot
     * never opened is ignored.
     */
    fun update(index: Int, text: String): Boolean {
        if (index !in texts.indices) return false
        val trimmed = text.trimmed
        if (texts[index] == trimmed) return false
        texts[index] = trimmed
        return true
    }

    /** Its socket will say nothing more about this segment. */
    fun end(index: Int) {
        open -= index
    }

    fun isOpen(index: Int): Boolean = index in open

    fun has(index: Int): Boolean = index in texts.indices

    /** True once every segment has finished — the only moment a text is final. */
    val isSettled: Boolean get() = open.isEmpty()

    /** Every segment in the order its audio was spoken, empty ones dropped. */
    val joined: String get() = texts.filter { it.isNotEmpty() }.joinToString(" ")
}
