package com.junbingao.remotecontrol.android.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The gateway's frames: 16 kHz mono PCM16, little-endian, a tenth of a second at a time. */
class MicrophoneChunkTest {
    @Test
    fun aChunkIsATenthOfASecondOfSixteenKilohertzAudio() {
        assertEquals(16_000, MicrophoneCapture.sampleRate)
        assertEquals(1_600, MicrophoneCapture.chunkFrames)
        assertEquals(3_200, MicrophoneCapture.chunkBytes)
    }

    @Test
    fun samplesLeaveLowByteFirst() {
        val samples = shortArrayOf(0x0102, -2, Short.MAX_VALUE, Short.MIN_VALUE)
        assertArrayEquals(
            byteArrayOf(0x02, 0x01, 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()),
            Pcm16.littleEndian(samples, samples.size),
        )
    }

    @Test
    fun onlyTheSamplesReadAreSent() {
        assertEquals(4, Pcm16.littleEndian(shortArrayOf(1, 2, 3, 4), 2).size)
    }

    @Test
    fun silenceIsTheBottomOfTheMeterAndAFullScaleToneItsTop() {
        assertEquals(0.0, InputLevel.from(Pcm16.rms(ShortArray(1600), 1600)), 0.0)
        val square = ShortArray(1600) { if (it % 2 == 0) Short.MAX_VALUE else (-Short.MAX_VALUE).toShort() }
        assertEquals(1.0, Pcm16.rms(square, square.size), 0.001)
        assertEquals(1.0, InputLevel.from(Pcm16.rms(square, square.size)), 0.0)
    }
}
