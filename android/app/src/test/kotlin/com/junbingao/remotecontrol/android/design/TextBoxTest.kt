package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.junbingao.remotecontrol.android.harness.IPhoneScreenshotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SwiftUI's text box, which every row's height is built from: a line is SF's own 1.19 of the size
 * tall with its baseline one SF ascent down, each further line adds the style's leading, and a line
 * of Chinese stands in the same box as a line of English (Android would make it taller).
 */
class TextBoxTest : IPhoneScreenshotTest() {
    private data class Measured(val height: Float, val baseline: Float)

    private val shown = mutableStateOf("" to SystemFont.body)
    private var measured: Measured? = null
    private var drawn = false

    // A rule takes its content once, so every case swaps the words and style it draws instead.
    private fun measure(text: String, style: TextStyle): Measured {
        if (!drawn) {
            drawn = true
            compose.setContent {
                val (words, font) = shown.value
                Box(
                    Modifier.onGloballyPositioned { coordinates ->
                        measured = Measured(coordinates.size.height / PIXELS, coordinates[FirstBaseline] / PIXELS)
                    },
                ) { Text(words, style = font) }
            }
        }
        shown.value = text to style
        compose.waitForIdle()
        return measured!!
    }

    @Test
    fun aLineIsSfsOwnHeightWithItsBaselineOneAscentDown() {
        for (style in listOf(SystemFont.caption, SystemFont.callout, SystemFont.body, SystemFont.largeTitle)) {
            val size = style.fontSize.value
            val line = measure("Hxg", style)
            assertEquals(1.193f * size, line.height, PIXEL)
            assertEquals(0.952f * size, line.baseline, PIXEL)
        }
    }

    @Test
    fun aFurtherLineAddsTheStylesLeading() {
        assertEquals(1.193f * 12 + 16, measure("Hxg\nHxg", SystemFont.caption).height, PIXEL)
        assertEquals(1.193f * 16 + 21, measure("Hxg\nHxg", SystemFont.callout).height, PIXEL)
        assertEquals(1.193f * 16 + 42, measure("Hxg\nHxg\nHxg", SystemFont.callout).height, PIXEL)
    }

    @Test
    fun chineseStandsInTheSameBoxAsEnglish() {
        val english = measure("Hxg", SystemFont.callout)
        val chinese = measure("设备", SystemFont.callout)
        assertEquals(english.height, chinese.height, PIXEL)
        assertEquals(english.baseline, chinese.baseline, PIXEL)
        assertEquals(measure("Hxg\nHxg", SystemFont.callout).height, measure("设备\n会话", SystemFont.callout).height, PIXEL)
    }

    @Test
    fun chineseTakesNoLatinTracking() {
        assertTrue(holdsCjk("设置"))
        assertTrue(holdsCjk("Claude Code 运行中"))
        assertTrue(holdsCjk("（"))
        assertFalse(holdsCjk("mac-studio-office"))
        assertFalse(holdsCjk("Hxg · 3"))
    }

    @Test
    fun trackingFollowsTheMeasuredTable() {
        assertEquals(0.023f, SystemFont.tracking(8f), 1e-6f)
        assertEquals(0.0025f, SystemFont.tracking(17f), 1e-6f)
        assertEquals(0.0155f, SystemFont.tracking(14f), 1e-6f)
        assertEquals(0.008f, SystemFont.tracking(40f), 1e-6f)
        assertEquals(SystemFont.tracking(17f), SystemFont.body.letterSpacing.value, 1e-6f)
    }

    @Test
    fun heavierWeightsAreSetLooserAsSfsWidenMoreThanRobotos() {
        assertEquals(0.018f, SystemFont.tracking(17f, FontWeight.Medium), 1e-6f)
        assertEquals(0.0245f, SystemFont.tracking(17f, FontWeight.SemiBold), 1e-6f)
        assertEquals(SystemFont.tracking(17f, FontWeight.SemiBold), SystemFont.headline.letterSpacing.value, 1e-6f)
        assertEquals(SystemFont.tracking(16f, FontWeight.SemiBold), Theme.Text.title.letterSpacing.value, 1e-6f)
        assertEquals("light is set as regular", SystemFont.tracking(30f), SystemFont.tracking(30f, FontWeight.Light), 1e-6f)
        val mono = SystemFont.footnote.monospaced()
        assertEquals("a monospaced style keeps its spacing", mono.letterSpacing, mono.weight(FontWeight.SemiBold).letterSpacing)
    }

    private companion object {
        /** Three pixels a point at the iPhone's density. */
        const val PIXELS = 3f

        /** A box lands on whole pixels, a third of a point. */
        const val PIXEL = 0.34f
    }
}
