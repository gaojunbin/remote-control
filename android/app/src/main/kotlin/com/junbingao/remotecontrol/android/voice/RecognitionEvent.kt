package com.junbingao.remotecontrol.android.voice

/**
 * What Android's recogniser said, by the run it belongs to.
 *
 * One `SpeechRecognizer` request ends when the speaker pauses, so a dictation with no maximum
 * duration is a chain of runs, numbered from 0 each time listening starts. Each run is one slot
 * of the core's `TranscriptSegments`, exactly as each rolled-over request is on the iPhone
 * (`SystemSpeechRecognizer.swift`): stage 2 begins a slot on [Began], updates it on [Partial] and
 * [Final], ends it on [Ended], and publishes the joined text as final after [Finished].
 */
sealed interface RecognitionEvent {
    /** A run started listening; it is the slot the next results belong to. */
    data class Began(val segment: Int) : RecognitionEvent

    /** The run's best transcription so far. */
    data class Partial(val segment: Int, val text: String) : RecognitionEvent

    /** The run's last transcription. */
    data class Final(val segment: Int, val text: String) : RecognitionEvent

    /** The run is over, with or without a final. */
    data class Ended(val segment: Int) : RecognitionEvent

    /** How loud the speaker is, on `InputLevel`'s 0…1 scale. */
    data class Level(val value: Double) : RecognitionEvent

    /** Dictation cannot go on; whatever the runs before it heard stands. */
    data class Failed(val failure: SpeechInputFailure) : RecognitionEvent

    /** After `finish()`: the last run has ended and nothing more will come. */
    data object Finished : RecognitionEvent
}
