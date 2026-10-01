package com.junbingao.remotecontrol.win.voice

/** Where dictation has got to (`VoiceState` in `web/src/features/voice/useVoice.ts`). */
enum class VoiceState {
    idle, starting, listening, finishing, error;

    /** The microphone or its last transcript still has the field. */
    val isBusy: Boolean get() = this == starting || this == listening || this == finishing
}

/**
 * `web/src/features/voice/primarySlot.ts`: what the composer's one primary slot holds, from the two
 * things that can claim it — where dictation has got to, and whether the polish model is still
 * writing the words back.
 *
 * `docs/DESIGN.md` § "The composer" → **Done becomes a spinner, and the spinner becomes Send**: Done
 * while the microphone is live, a spinner for as long as the words are still on their way, and Send
 * the moment the field holds what will be sent.
 */
enum class PrimarySlot {
    done, working, send;

    companion object {
        fun of(voice: VoiceState, polish: PolishProgress): PrimarySlot = when (voice) {
            // A live dictation owns the slot whatever else is out: the words are still being
            // spoken, so the only thing to offer is the way out.
            VoiceState.starting, VoiceState.listening -> done
            // Done was clicked and the backend's final transcript is not here yet.
            VoiceState.finishing -> working
            // The field holds the dictated words already; only an answer still on its way stands
            // between them and Send.
            VoiceState.idle, VoiceState.error -> if (polish == PolishProgress.polishing) working else send
        }
    }
}
