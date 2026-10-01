package com.junbingao.remotecontrol.win.platform

import javax.sound.sampled.AudioFormat
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `web/tests/downsample.test.ts`: any-rate samples in, 16 kHz PCM16LE out. No microphone is opened. */
class DownsampleTests {
    @Test
    fun floatsAreClampedAndScaledIntoTheSigned16BitRange() {
        assertEquals(0, Downsample.pcm16(0f).toInt())
        assertEquals(32_767, Downsample.pcm16(1f).toInt())
        assertEquals(-32_768, Downsample.pcm16(-1f).toInt())
        assertEquals(32_767, Downsample.pcm16(4f).toInt())
        assertEquals(-32_768, Downsample.pcm16(-4f).toInt())
    }

    @Test
    fun sixteenKilohertzPassesThroughUntouched() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertContentEquals(input, Downsample.to16k(input, Downsample.targetRate))
    }

    @Test
    fun fortyEightKilohertzIsBoxFilteredToAThird() {
        val output = Downsample.to16k(FloatArray(48_000) { 1f }, 48_000.0)
        assertEquals(16_000, output.size)
        assertTrue(abs(output.first() - 1) < 1e-6 && abs(output.last() - 1) < 1e-6)
    }

    @Test
    fun eachSourceWindowIsAveragedRatherThanSampled() {
        // 44.1 kHz alternating +1/−1 must average towards zero, not alias to +1.
        val output = Downsample.to16k(FloatArray(44_100) { if (it % 2 == 0) 1f else -1f }, 44_100.0)
        assertEquals(16_000, output.size)
        assertTrue(Downsample.peakLevel(output) < 0.7f)
    }

    @Test
    fun inputBelowTheTargetRateIsRefused() {
        assertFailsWith<Downsample.RateTooLow> { Downsample.to16k(FloatArray(10), 8_000.0) }
    }

    @Test
    fun thePeakLevelDrivesTheWaveform() {
        assertTrue(abs(Downsample.peakLevel(floatArrayOf(0.1f, -0.6f, 0.3f)) - 0.6f) < 1e-6)
        assertEquals(0f, Downsample.peakLevel(FloatArray(0)))
        assertEquals(1f, Downsample.peakLevel(floatArrayOf(9f)))
    }

    @Test
    fun framesAre120MillisecondsOfLittleEndianPCM16() {
        val chunker = PcmChunker(Downsample.targetRate)
        assertTrue(chunker.push(FloatArray(1_000)).isEmpty())
        val frames = chunker.push(FloatArray(Downsample.frameSamples))
        assertEquals(1, frames.size)
        assertEquals(Downsample.frameSamples * 2, frames[0].size)
    }

    @Test
    fun samplesAreWrittenLittleEndian() {
        val frame = PcmChunker(Downsample.targetRate, frameSamples = 2).push(floatArrayOf(1f, -1f)).first()
        assertContentEquals(byteArrayOf(0xff.toByte(), 0x7f, 0x00, 0x80.toByte()), frame)
    }

    @Test
    fun theRemainderWaitsForTheNextPush() {
        val chunker = PcmChunker(Downsample.targetRate, frameSamples = 4)
        val first = chunker.push(FloatArray(6))
        val second = chunker.push(FloatArray(2))
        assertTrue(first.size == 1 && second.size == 1)
        assertNull(chunker.flush())
    }

    @Test
    fun aPartialTrailingFrameIsFlushedExactlyOnce() {
        val chunker = PcmChunker(Downsample.targetRate, frameSamples = 4)
        chunker.push(FloatArray(3))
        assertEquals(6, chunker.flush()?.size)
        assertNull(chunker.flush())
    }

    @Test
    fun audioIsResampledOnTheWayInSoFramesAreAlways16Kilohertz() {
        // 30 000 samples at 48 kHz is 10 000 at 16 kHz: a hundred frames.
        assertEquals(100, PcmChunker(48_000.0, frameSamples = 100).push(FloatArray(30_000)).size)
    }

    /** What the recorder asks a device for, and how it reads the bytes back — without a device. */
    @Test
    fun theDeviceIsAskedFor16KilohertzFirstAndReadOnItsFirstChannel() {
        val first = MicFormats.candidates.first()
        assertEquals(16_000f, first.sampleRate)
        assertEquals(1, first.channels)
        assertTrue(MicFormats.candidates.all { it.sampleRate >= 16_000f || it.sampleRate == 22_050f })
        val stereo = AudioFormat(48_000f, 16, 2, true, false)
        assertEquals(4_800 * 4, MicFormats.blockBytes(stereo))
        // Two stereo frames: left +0.5 then −1, right ignored.
        val bytes = byteArrayOf(0x00, 0x40, 0x11, 0x11, 0x00, 0x80.toByte(), 0x22, 0x22)
        assertContentEquals(floatArrayOf(0.5f, -1f), MicFormats.firstChannel(bytes, bytes.size, stereo))
    }
}
