package com.junbingao.remotecontrol.android.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** The stop slider's arithmetic: a touch snaps to the nearest stop and never past the ends. */
class StopSliderGeometryTest {
    private val thumb = 24f
    private val travel = 300f

    @Test
    fun stopsAreSpacedEvenlyAlongTheTravel() {
        assertEquals(100f, StopSliderGeometry.step(travel, 4), 1e-6f)
        assertEquals(0f, StopSliderGeometry.step(travel, 1), 1e-6f)
    }

    @Test
    fun aTouchSnapsToTheNearestStop() {
        assertEquals(0, StopSliderGeometry.stop(thumb / 2, travel, thumb, 4))
        assertEquals(1, StopSliderGeometry.stop(thumb / 2 + 60f, travel, thumb, 4))
        assertEquals(1, StopSliderGeometry.stop(thumb / 2 + 140f, travel, thumb, 4))
        assertEquals(3, StopSliderGeometry.stop(thumb / 2 + travel, travel, thumb, 4))
    }

    @Test
    fun aTouchBeyondTheTrackKeepsToItsEnds() {
        assertEquals(0, StopSliderGeometry.stop(-50f, travel, thumb, 4))
        assertEquals(3, StopSliderGeometry.stop(1000f, travel, thumb, 4))
        assertEquals("one stop is the only answer", 0, StopSliderGeometry.stop(200f, travel, thumb, 1))
        assertEquals("a track with no room snaps to the first", 0, StopSliderGeometry.stop(200f, 0f, thumb, 4))
    }
}
