package com.junbingao.remotecontrol.android.design

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ios/VerificationUI` § "One run of an action at a time". */
class OneAtATimeTest {
    @Test
    fun aControlThatIsAlreadyActingStartsNoSecondRun() = runTest {
        val gate = OneAtATime()
        var runs = 0
        var busyWhileRunning = false
        gate.run {
            runs += 1
            busyWhileRunning = gate.isBusy
            gate.run { runs += 1 }
        }
        assertEquals("a control that is already acting starts no second run", 1, runs)
        assertTrue("and says so while it acts, so the button can be disabled", busyWhileRunning)
        assertFalse("and comes back when the action returns", gate.isBusy)
    }

    @Test
    fun aFailedRunStillFreesTheControl() = runTest {
        val gate = OneAtATime()
        runCatching { gate.run { error("the request failed") } }
        assertFalse(gate.isBusy)
        var ran = false
        gate.run { ran = true }
        assertTrue(ran)
    }
}
