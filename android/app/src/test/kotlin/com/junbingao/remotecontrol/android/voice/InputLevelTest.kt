package com.junbingao.remotecontrol.android.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The checks `ios/VerificationUI/main.swift` makes of `InputLevel`. */
class InputLevelTest {
    @Test
    fun silenceIsTheBottomOfTheScale() {
        assertEquals(0.0, InputLevel.from(0.0), 0.0)
    }

    @Test
    fun andSoIsAQuietRoom() {
        assertEquals(0.0, InputLevel.from(10.0.pow(InputLevel.floorDB / 20)), 1e-9)
    }

    @Test
    fun conversationalSpeechReachesTheTop() {
        assertEquals(1.0, InputLevel.from(10.0.pow(InputLevel.ceilingDB / 20)), 1e-9)
    }

    @Test
    fun theMiddleOfAVoicesRangeSitsInTheMiddleOfTheGlows() {
        val middle = InputLevel.from(10.0.pow(-31.0 / 20))
        assertTrue(middle > 0.45 && middle < 0.55)
    }

    @Test
    fun nothingLouderThanTheCeilingOvershootsIt() {
        assertTrue(InputLevel.from(1.0) <= 1.0)
    }
}
