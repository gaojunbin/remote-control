package com.junbingao.remotecontrol.core.state

/**
 * Identifies the composer a dictation belongs to.
 *
 * Everything that could make a draft land in the wrong place is part of the identity: a different
 * gateway, account, device or session resets dictation rather than pasting the transcript
 * somewhere the user did not expect.
 */
data class VoiceDraftTarget(val account: String, val deviceID: String, val sessionID: String? = null) {
    fun matches(other: VoiceDraftTarget): Boolean = this == other

    /**
     * Append a transcript to the draft the user already had.
     *
     * This is a pure join. Replacing rather than accumulating the dictated segment is the app's
     * inline voice draft session's job: it keeps the original draft and re-derives from it on every
     * partial.
     */
    fun inserting(transcript: String, into: String, currentTarget: VoiceDraftTarget): String? {
        if (!matches(currentTarget)) return null
        val text = transcript.trimmed
        if (text.isEmpty()) return null
        // Amendment A29: the join is `DictationSpan`'s, so a span can rebuild this exact draft when
        // a polished answer comes back for it.
        return DictationSpan.merge(into, text)
    }
}

enum class VoiceInputPhase(val rawValue: String) {
    idle("idle"),
    requestingPermission("requestingPermission"),
    listening("listening"),
    finishing("finishing"),
    review("review"),
    failed("failed");

    val capturesAudio: Boolean get() = this == listening
    val isBusy: Boolean get() = this == requestingPermission || this == listening || this == finishing

    companion object {
        operator fun invoke(rawValue: String): VoiceInputPhase? = entries.firstOrNull { it.rawValue == rawValue }
    }
}
