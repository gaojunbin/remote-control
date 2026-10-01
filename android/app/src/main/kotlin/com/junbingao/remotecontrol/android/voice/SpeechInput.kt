package com.junbingao.remotecontrol.android.voice

/**
 * Why dictation stopped or could not start — `SpeechInputFailure` in `VoiceInputController.swift`,
 * whose cases the composer's status line words one by one.
 */
sealed class SpeechInputFailure : Exception() {
    /** No recogniser for this language on the phone. */
    data object Unsupported : SpeechInputFailure()

    /** Speech recognition was refused. Android asks for no such permission; kept for the port. */
    data object SpeechPermission : SpeechInputFailure()

    /** The microphone was refused. */
    data object MicrophonePermission : SpeechInputFailure()

    /** A recogniser exists but cannot take a request right now. */
    data object Unavailable : SpeechInputFailure()

    /** The microphone could not be opened or stopped delivering audio. */
    data object Recording : SpeechInputFailure()

    /** The recogniser gave up. */
    data object Recognition : SpeechInputFailure()

    /** Something else took the microphone — a call, another app. */
    data object Interrupted : SpeechInputFailure()
}

/** What a dictation backend reports while it listens. */
sealed interface SpeechInputEvent {
    /** Everything heard so far, and whether it is the last word. */
    data class Transcript(val text: String, val isFinal: Boolean) : SpeechInputEvent

    /** How loud the microphone is, on `InputLevel`'s 0…1 scale. */
    data class Level(val value: Double) : SpeechInputEvent

    data class Failure(val failure: SpeechInputFailure) : SpeechInputEvent
}

/**
 * A dictation backend: the phone's recogniser or the gateway's. The controller that drives it
 * (`VoiceInputController`, which needs the core's `VoiceInputPhase`) arrives in stage 2 and talks
 * to either through this.
 */
interface SpeechInputPlatform {
    /**
     * How long this backend may take to deliver a final transcript after [finish], in seconds.
     * On-device recognition answers almost at once; the gateway has to transcribe up to two
     * minutes of audio.
     */
    val finishGracePeriod: Double get() = 2.0

    /** Ask for what listening needs, throwing the [SpeechInputFailure] that stops it. */
    suspend fun requestPermission()

    /** Start listening. Events arrive on the main thread; throws a [SpeechInputFailure]. */
    fun start(onEvent: (SpeechInputEvent) -> Unit)

    /** Stop listening and deliver what was heard. */
    fun finish()

    /** Stop and drop everything. */
    fun cancel()
}
