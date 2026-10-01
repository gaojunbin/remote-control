package com.junbingao.remotecontrol.android.design

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ios/VerificationUI` § "The device row says less": the laptop before a device and the folder
 * before a working directory, on lucide's grid. On Robolectric, whose native graphics measure a
 * path's bounds as a phone does.
 */
@RunWith(AndroidJUnit4::class)
class GlyphGeometryTest {
    private fun assertRect(expected: Rect, actual: Rect, label: String) {
        assertEquals("$label left", expected.left, actual.left, 0.01f)
        assertEquals("$label top", expected.top, actual.top, 0.01f)
        assertEquals("$label right", expected.right, actual.right, 0.01f)
        assertEquals("$label bottom", expected.bottom, actual.bottom, 0.01f)
    }

    @Test
    fun theLaptopSpansTheGridFromTheBaseLinesEndsToTheScreensTop() {
        assertRect(Rect(2f, 4f, 22f, 20f), LaptopShape.path(Rect(0f, 0f, 24f, 24f)).getBounds(), "the laptop")
        assertTrue("the base is a line under the screen, not touching it", LaptopShape.baseY > LaptopShape.screen.bottom)
        assertTrue("and the screen's corners are rounded", LaptopShape.screenCorner > 0)
        assertRect(Rect(4f, 8f, 44f, 40f), LaptopShape.path(Rect(0f, 0f, 48f, 48f)).getBounds(), "twice the box")
    }

    @Test
    fun theLaptopsBaseLineSitsWhereARowTitlesBaselineDoes() {
        assertEquals(20f / 24f, LaptopShape.baselineFraction, 1e-6f)
    }

    @Test
    fun theStrokeIsLucidesOneAndAHalfUnitsWithRoundEnds() {
        assertEquals("at grid size the stroke is lucide's 1.5 units", 1.5f, OutlineGlyph.strokeWidth(24f), 1e-6f)
        assertEquals("and it thins with the box", 1.25f, OutlineGlyph.strokeWidth(20f), 1e-6f)
        assertEquals("with round caps", StrokeCap.Round, OutlineGlyph.stroke(24f).cap)
        assertEquals("and round joins", StrokeJoin.Round, OutlineGlyph.stroke(24f).join)
    }

    @Test
    fun theFolderSpansTheGridFromTheTabsTopToItsBottom() {
        assertRect(Rect(2f, 3f, 22f, 20f), FolderShape.path(Rect(0f, 0f, 24f, 24f)).getBounds(), "the folder")
        val tab = FolderShape.corners
        assertTrue("the tab rises up and to the left from the body's top edge", tab[4].y < tab[3].y && tab[4].x < tab[3].x)
        assertEquals("the body's top edge is level to the tab", tab[2].y, tab[3].y, 0f)
        assertEquals("and the tab's top is level to the left edge", tab[4].y, tab[5].y, 0f)
        assertTrue("and its corners are rounded", FolderShape.cornerRadius > 0)
        assertRect(Rect(4f, 6f, 44f, 40f), FolderShape.path(Rect(0f, 0f, 48f, 48f)).getBounds(), "twice the box")
    }
}
