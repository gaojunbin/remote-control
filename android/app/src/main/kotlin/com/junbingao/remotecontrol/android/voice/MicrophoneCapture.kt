package com.junbingao.remotecontrol.android.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import com.junbingao.remotecontrol.android.permissions.isPermissionGranted
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The microphone as the gateway's recogniser hears it: 16 kHz mono PCM16, little-endian, in
 * 100 ms chunks — the frames `GatewaySpeechRecognizer.swift` converts the hardware format into and
 * feeds `STTSocket`, which the protocol specifies for `WS /ws/stt`. Android records that format
 * directly, so nothing is converted.
 *
 * Audio leaves the phone only through the caller, which is the difference from on-device
 * recognition that the Transcribe setting states. The voice-recognition source is the one tuned
 * for a recogniser, with the gain control a call would add left off where the phone allows it, as
 * the iPhone records in its measurement mode.
 */
class MicrophoneCapture(private val context: Context) : AudioCapture {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var record: AudioRecord? = null
    private var silenced: AudioManager.AudioRecordingCallback? = null
    private var watcher: ExecutorService? = null

    val isRunning: Boolean get() = running

    /**
     * Start capturing. Every full chunk is handed to [onChunk] with its level on the capture
     * thread; [onFailure] reports the microphone going away or being taken by something else.
     * Throws [SpeechInputFailure.MicrophonePermission] without the permission and
     * [SpeechInputFailure.Recording] when the microphone cannot be opened.
     */
    @SuppressLint("MissingPermission") // Checked on the line above the recorder is built.
    override fun start(onChunk: (pcm: ByteArray, level: Double) -> Unit, onFailure: (SpeechInputFailure) -> Unit) {
        stop()
        if (!isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)) throw SpeechInputFailure.MicrophonePermission
        val minimum = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minimum <= 0) throw SpeechInputFailure.Recording
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, CHANNEL, ENCODING,
                maxOf(minimum, chunkBytes * 4))
        } catch (_: IllegalArgumentException) {
            throw SpeechInputFailure.Recording
        } catch (_: SecurityException) {
            throw SpeechInputFailure.MicrophonePermission
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw SpeechInputFailure.Recording
        }
        try {
            recorder.startRecording()
        } catch (_: IllegalStateException) {
            recorder.release()
            throw SpeechInputFailure.Recording
        }
        record = recorder
        running = true
        watchForSilencing(recorder, onFailure)
        worker = thread(name = "rc-microphone", isDaemon = true) { pump(recorder, onChunk, onFailure) }
    }

    /** Stop capturing and let the microphone go. No chunk is delivered after this returns. */
    override fun stop() {
        running = false
        // Stopping from inside a chunk or a failure report is stopping from the capture thread,
        // which cannot wait for itself.
        worker?.takeIf { it !== Thread.currentThread() }?.join(JOIN_MILLIS)
        worker = null
        silenced?.let { record?.unregisterAudioRecordingCallback(it) }
        silenced = null
        watcher?.shutdown()
        watcher = null
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // Already stopped by the system; releasing is all that is left.
            }
            it.release()
        }
        record = null
    }

    private fun pump(recorder: AudioRecord, onChunk: (ByteArray, Double) -> Unit, onFailure: (SpeechInputFailure) -> Unit) {
        val samples = ShortArray(chunkFrames)
        while (running) {
            var filled = 0
            while (running && filled < chunkFrames) {
                val read = recorder.read(samples, filled, chunkFrames - filled)
                if (read < 0) {
                    running = false
                    onFailure(SpeechInputFailure.Recording)
                    return
                }
                filled += read
            }
            if (!running) return
            onChunk(Pcm16.littleEndian(samples, filled), InputLevel.from(Pcm16.rms(samples, filled)))
        }
    }

    /**
     * A call or another app taking the microphone silences this one rather than stopping it;
     * that is the iPhone's audio-session interruption, and it ends dictation the same way.
     */
    private fun watchForSilencing(recorder: AudioRecord, onFailure: (SpeechInputFailure) -> Unit) {
        val session = recorder.audioSessionId
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
                if (mine.isClientSilenced && running) onFailure(SpeechInputFailure.Interrupted)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        recorder.registerAudioRecordingCallback(executor, callback)
        watcher = executor
        silenced = callback
    }

    companion object {
        /** `STTSocket.sampleRate`. */
        const val sampleRate = 16_000

        /** How much audio one chunk carries. */
        const val chunkMillis = 100

        /** Samples in one chunk: 1600. */
        const val chunkFrames = sampleRate * chunkMillis / 1000

        /** Bytes in one chunk of 16-bit samples: 3200. */
        const val chunkBytes = chunkFrames * 2

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val JOIN_MILLIS = 500L
    }
}

/** The arithmetic on 16-bit samples the capture needs, apart from the microphone. */
internal object Pcm16 {
    /** The first [count] samples as the wire's little-endian bytes. */
    fun littleEndian(samples: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (index in 0 until count) {
            val value = samples[index].toInt()
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /** Root mean square of the first [count] samples, on the −1…1 scale `InputLevel` reads. */
    fun rms(samples: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (index in 0 until count) {
            val value = samples[index] / 32768.0
            sum += value * value
        }
        return kotlin.math.sqrt(sum / count)
    }
}
