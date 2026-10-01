package com.junbingao.remotecontrol.win.voice

/**
 * `web/src/features/voice/draft.ts`: where a live transcript lands in the message field.
 *
 * Dictation never replaces what was already typed: the transcript is appended to the draft the mic
 * button was pressed on, after a space, and every update rewrites only that tail, so a correction
 * from the recogniser leaves no duplicate behind.
 */
object DictationDraft {
    fun merge(base: String, transcript: String): String {
        if (transcript.isEmpty()) return base
        if (base.isEmpty()) return transcript
        return if (base.last().isWhitespace()) base + transcript else "$base $transcript"
    }
}
