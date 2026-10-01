package com.junbingao.remotecontrol.core.state

/**
 * What the one primary slot of the composer's control row holds, derived from the two things that
 * can claim it: where dictation has got to, and whether the model is still writing the words back.
 *
 * `docs/DESIGN.md` § "The composer" → **Done becomes a spinner, and the spinner becomes Send**. The
 * rule is one sentence long: Done while the microphone is live, a spinner for as long as the words
 * are still on their way, and Send the moment the field holds what will be sent. It lives here, away
 * from any view, because the interesting part is the pairs — a phase of dictation against a phase
 * of polish — and every one of them can be checked without a screen.
 */
enum class ComposerPrimarySlot(val rawValue: String) {
    /** The one way out of a running dictation. */
    done("done"),

    /** Nothing in the slot can be tapped: the backend's final transcript, or the model's answer, is still on its way. */
    working("working"),

    /** The field holds what will be sent. */
    send("send");

    companion object {
        val allCases: List<ComposerPrimarySlot> get() = entries

        operator fun invoke(rawValue: String): ComposerPrimarySlot? = entries.firstOrNull { it.rawValue == rawValue }

        /**
         * Amendment A43: `returning` is an edited queued message on its way back into the line.
         * Like a polish still out, it is the app's move, and Send is not offered again until the
         * device has answered.
         */
        fun of(voice: VoiceInputPhase, polish: PolishPhase, returning: Boolean = false): ComposerPrimarySlot =
            when (voice) {
                // A live dictation owns the row whatever else is out. It cannot in fact be
                // polishing — starting a dictation drops the last answer — but the rule reads the
                // same either way: the words are still being spoken, so the only thing to offer is
                // the way out of speaking them.
                VoiceInputPhase.listening, VoiceInputPhase.requestingPermission -> done
                // Done was tapped and the transcript is not final yet.
                VoiceInputPhase.finishing -> working
                // Dictation is over: the field holds the dictated words already, and only a polish
                // still out stands between them and Send.
                VoiceInputPhase.idle, VoiceInputPhase.review, VoiceInputPhase.failed ->
                    if (polish == PolishPhase.Polishing || returning) working else send
            }
    }
}
