package com.junbingao.remotecontrol.win.platform

/** Why the microphone could not be used. */
enum class RecorderError { denied, unsupported, failed }

/** What a recorder hands on, on the main thread. */
class RecorderHandlers(
    /** One frame of 16 kHz PCM16LE mono, for the speech socket. */
    val onFrame: (ByteArray) -> Unit,
    /** The loudest sample of the last block, 0 to 1, which drives the waveform. */
    val onLevel: (Double) -> Unit,
    val onError: (RecorderError) -> Unit,
)

/**
 * The microphone, as the web's `useVoice` uses it (`VoiceRecorder` in `useVoice.ts`) and the Mac's
 * dictation drives it: start capturing, and stop, handing on the tail of the audio first. Previews
 * and the demo speak from a script and never open the microphone.
 */
interface VoiceRecorder {
    /** Start capturing; false when it could not, after telling `onError` why. */
    suspend fun start(): Boolean

    /** Stop, handing on the tail of the audio first. */
    suspend fun stop()
}
