package com.junbingao.remotecontrol.android.voice

/**
 * The microphone as the gateway's recogniser takes it: 16 kHz mono PCM16 chunks with their level,
 * on a thread of the capture's own. [MicrophoneCapture] is the phone's; a test hands in audio it
 * scripted, as the iPhone's checks never open a microphone either.
 */
interface AudioCapture {
    /** Start capturing; throws a [SpeechInputFailure] when the microphone cannot be had. */
    fun start(onChunk: (pcm: ByteArray, level: Double) -> Unit, onFailure: (SpeechInputFailure) -> Unit)

    /** Stop capturing. No chunk is delivered after this returns. */
    fun stop()
}
