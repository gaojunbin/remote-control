package com.junbingao.remotecontrol.win.platform

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * `web/src/features/voice/downsample.ts`, as the Mac app ports it: audio for the speech socket —
 * mono samples at whatever rate the input runs, out as 16 kHz PCM16LE in fixed-size frames. Pure,
 * so the conversion is checked without a microphone.
 */
object Downsample {
    const val targetRate = 16_000.0

    /** About 120 ms per frame at 16 kHz, inside the 100–200 ms the contract recommends, as the web's worklet sends. */
    const val frameSamples = 1920

    class RateTooLow : Exception("The input runs below 16 kHz.")

    /**
     * A sample clamped to ±1 and scaled into the signed 16-bit range, rounded as `Math.round`
     * rounds: halves go up.
     */
    fun pcm16(sample: Float): Short {
        val clamped = max(-1.0, min(1.0, sample.toDouble()))
        return floor((if (clamped < 0) clamped * 0x8000 else clamped * 0x7fff) + 0.5).toInt().toShort()
    }

    /** A box filter: each output sample is the average of the source window it covers, so the step down does not alias. */
    fun to16k(input: FloatArray, rate: Double): FloatArray {
        if (rate == targetRate || input.isEmpty()) return input
        if (rate < targetRate) throw RateTooLow()
        val ratio = rate / targetRate
        val count = floor(input.size / ratio).toInt()
        return FloatArray(count) { index ->
            val start = floor(index * ratio).toInt()
            val end = min(input.size, floor((index + 1) * ratio).toInt())
            if (end <= start) {
                0f
            } else {
                var sum = 0f
                for (i in start until end) sum += input[i]
                sum / (end - start)
            }
        }
    }

    /** The loudest sample of a block, which drives the waveform. */
    fun peakLevel(input: FloatArray): Float = min(1f, input.fold(0f) { peak, sample -> max(peak, abs(sample)) })

    /** Little-endian PCM16, as the socket sends it. */
    fun encode(samples: FloatArray, from: Int = 0, to: Int = samples.size): ByteArray {
        val data = ByteArray((to - from) * 2)
        for (i in from until to) {
            val value = pcm16(samples[i]).toInt()
            data[(i - from) * 2] = (value and 0xFF).toByte()
            data[(i - from) * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return data
    }
}

/** Collects resampled audio and hands it on in frames of one size; a partial frame waits for the next block, or for `flush`. */
class PcmChunker(private val inputRate: Double, private val frameSamples: Int = Downsample.frameSamples) {
    private var buffer = FloatArray(0)

    fun push(block: FloatArray): List<ByteArray> {
        buffer += Downsample.to16k(block, inputRate)
        val frames = mutableListOf<ByteArray>()
        var start = 0
        while (buffer.size - start >= frameSamples) {
            frames += Downsample.encode(buffer, start, start + frameSamples)
            start += frameSamples
        }
        buffer = buffer.copyOfRange(start, buffer.size)
        return frames
    }

    /** What is left, as one short frame: the contract allows a partial one. */
    fun flush(): ByteArray? {
        if (buffer.isEmpty()) return null
        return Downsample.encode(buffer).also { buffer = FloatArray(0) }
    }
}
