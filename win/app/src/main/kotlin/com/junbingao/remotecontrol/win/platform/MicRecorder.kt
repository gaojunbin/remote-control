package com.junbingao.remotecontrol.win.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

/**
 * `web/src/features/voice/recorder.ts` on Windows: the default input device through
 * `javax.sound.sampled`, 16 kHz mono PCM16 when the device gives it and otherwise its own rate,
 * resampled to 16 kHz by the web's box filter (`PcmChunker`), handed on in the web's frames, and the
 * peak of every block as the waveform's level.
 *
 * Windows grants the microphone in its privacy settings, and asks nobody: a desktop app it is
 * denied to reads silence. So the setting is read first, and a refusal is said as the web says a
 * denied permission (`MicrophoneAccess`).
 */
class MicRecorder(
    private val handlers: RecorderHandlers,
    private val main: CoroutineDispatcher = Dispatchers.Main,
) : VoiceRecorder {
    private var line: TargetDataLine? = null
    private var reader: Thread? = null
    private var chunker: PcmChunker? = null
    @Volatile private var running = false

    override suspend fun start(): Boolean {
        if (MicrophoneAccess.isDenied()) {
            handlers.onError(RecorderError.denied)
            return false
        }
        val opened = withContext(Dispatchers.IO) { open() }
        if (opened == null) {
            handlers.onError(RecorderError.unsupported)
            return false
        }
        val (input, format) = opened
        chunker = PcmChunker(format.sampleRate.toDouble())
        line = input
        running = true
        reader = thread(name = "microphone", isDaemon = true) { read(input, format) }
        return true
    }

    override suspend fun stop() {
        running = false
        val input = line ?: return
        line = null
        withContext(Dispatchers.IO) {
            input.stop()
            input.close()
            reader?.join(1000)
        }
        reader = null
        chunker?.flush()?.let(handlers.onFrame)
        chunker = null
    }

    /** The first format of `MicFormats.candidates` the default input device opens with. */
    private fun open(): Pair<TargetDataLine, AudioFormat>? {
        for (format in MicFormats.candidates) {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) continue
            try {
                val input = AudioSystem.getLine(info) as TargetDataLine
                input.open(format, MicFormats.blockBytes(format) * 4)
                input.start()
                return input to format
            } catch (_: LineUnavailableException) {
                continue
            } catch (_: IllegalArgumentException) {
                continue
            }
        }
        return null
    }

    /** Blocks of 100 ms off the device, their first channel as samples, handed to the main thread in order. */
    private fun read(input: TargetDataLine, format: AudioFormat) {
        val block = ByteArray(MicFormats.blockBytes(format))
        while (running) {
            val count = input.read(block, 0, block.size)
            if (count <= 0) continue
            val samples = MicFormats.firstChannel(block, count, format)
            kotlinx.coroutines.runBlocking(main) { consume(samples) }
        }
    }

    private fun consume(samples: FloatArray) {
        val chunker = chunker ?: return
        handlers.onLevel(Downsample.peakLevel(samples).toDouble())
        try {
            for (frame in chunker.push(samples)) handlers.onFrame(frame)
        } catch (_: Downsample.RateTooLow) {
            handlers.onError(RecorderError.unsupported)
        }
    }
}

/** The capture formats the recorder asks a device for, and how their bytes read as samples. Pure. */
object MicFormats {
    /** 16 kHz first, which needs no resampling; then the rates devices run at, each mono then stereo. */
    val candidates: List<AudioFormat> = listOf(16_000f, 48_000f, 44_100f, 32_000f, 96_000f, 88_200f, 22_050f)
        .flatMap { rate -> listOf(1, 2).map { channels -> AudioFormat(rate, 16, channels, true, false) } }

    /** 100 ms of the format, in whole frames. */
    fun blockBytes(format: AudioFormat): Int = (format.sampleRate / 10).toInt() * format.frameSize

    /** The first channel of little-endian signed 16-bit frames, as samples in ±1. */
    fun firstChannel(bytes: ByteArray, count: Int, format: AudioFormat): FloatArray {
        val frame = format.frameSize
        val frames = count / frame
        return FloatArray(frames) { index ->
            val at = index * frame
            val value = (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)
            value.toShort() / 32768f
        }
    }
}
