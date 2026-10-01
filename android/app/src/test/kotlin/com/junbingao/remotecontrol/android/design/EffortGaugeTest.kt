package com.junbingao.remotecontrol.android.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** `ios/VerificationUI` § "Amendment A44": where the effort gauge's needle points. */
class EffortGaugeTest {
    @Test
    fun aGaugeWithNoLevelToShowStandsItsNeedleUpright() {
        assertEquals(270.0, EffortGauge.needleAngle(null), 1e-9)
    }

    @Test
    fun theLowestLevelPointsAtTheLowerLeftAndTheHighestAtTheLowerRight() {
        assertEquals(135.0, EffortGauge.needleAngle(0.0), 1e-9)
        assertEquals(405.0, EffortGauge.needleAngle(1.0), 1e-9)
        assertEquals("a level past the ends stays on the arc", 405.0, EffortGauge.needleAngle(3.0), 1e-9)
    }

    @Test
    fun theArcIsCentredBelowTheSquaresMiddleSoTheInkIsBalanced() {
        assertEquals(0.5f, GaugeGeometry.pivot.x, 1e-6f)
        assertEquals(true, GaugeGeometry.pivot.y > 0.5f)
        assertEquals(0.5f - GaugeGeometry.lineWidth / 2, GaugeGeometry.radius, 1e-6f)
    }
}
