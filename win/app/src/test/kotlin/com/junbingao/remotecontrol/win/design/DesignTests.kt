package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.icons.SVGPath
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesignTests {
    /**
     * Chrome's baselines for the web's type, measured with a zero-height inline-block on the
     * baseline of each line (1× CSS px), on the Mac's face.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    fun baselinesAreTheBrowsers() {
        val measured = listOf(
            Triple(14f, 1.5f, 16f), Triple(13f, 1.5f, 14f), Triple(12f, 1.5f, 13f), Triple(11f, 1.5f, 12f),
            Triple(15f, 1.5f, 17f), Triple(17f, 1.5f, 18f), Triple(22f, 1.5f, 24f), Triple(30f, 1.5f, 34f),
            Triple(12f, 1.4f, 12f), Triple(13f, 1.4f, 14f), Triple(14f, 1.4f, 15f), Triple(14f, 1f, 12f),
            Triple(30f, 1f, 26f),
        )
        for ((size, lineHeight, baseline) in measured) {
            assertEquals(baseline, TextStyle(size = size, lineHeight = lineHeight).baseline, "${size}px × $lineHeight")
        }
    }

    /**
     * Chrome's widths for a middle dot line, the same under `lang="en"` and `lang="zh-Hans"`: the
     * dot is the system face's own, never a CJK font's.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    fun aMiddleDotMeasuresWhatTheBrowsersDoes() {
        val measured = listOf(
            Triple("Up next · 3", css(12f, weight = FontWeight.Medium), 62.70f),
            Triple("web · 13m", css(13f), 61.22f),
        )
        for ((text, style, chrome) in measured) {
            assertTrue(abs(Measure.width(text, style) - chrome) < 0.2f, "$text: ${Measure.width(text, style)}")
            val face = SystemFace.face(style.size, style.weight, style.mono)
            assertTrue(text.all { face.draws(it.code) }, "$text is drawn in one face")
        }
    }

    /** SF's size-specific tracking, which CoreText applies and Skia's shaping leaves out, read from its `trak` table. */
    @Test
    @EnabledOnOs(OS.MAC)
    fun theMacFaceCarriesCoreTextsTracking() {
        assertEquals(0f, SystemFace.face(12f, FontWeight.Normal, false).tracking, 0.0001f)
        assertEquals(-12f / 2048 * 13, SystemFace.face(13f, FontWeight.Normal, false).tracking, 0.0001f)
        assertEquals(-22f / 2048 * 14, SystemFace.face(14f, FontWeight.Medium, false).tracking, 0.0001f)
        assertEquals(27f / 2048 * 30, SystemFace.face(30f, FontWeight.SemiBold, false).tracking, 0.0001f)
        assertEquals(0f, SystemFace.face(13f, FontWeight.Normal, true).tracking)
        // CoreText's widths for the same lines, to a thousandth of a pixel.
        assertEquals(101.493f, Measure.width("Selected option", css(14f)), 0.01f)
        assertEquals(96.419f, Measure.width("Gallery", css(30f, weight = FontWeight.SemiBold)), 0.01f)
        assertEquals(85.250f, Measure.width("Small primary", css(13f, weight = FontWeight.Medium)), 0.01f)
    }

    @Test
    fun theTokensAreTheStylesheets() {
        assertTrue(LayoutSize.contentMax == 1080.dp && LayoutSize.headerH == 60.dp && LayoutSize.sidebarW == 264.dp)
        assertTrue(RowHeight.rowH == 64.dp && Radius.lg == 16.dp && Space.sp6 == 24.dp)
        assertEquals(0xFFF5F5F4.toInt(), Palette.canvas.toArgb())
        assertEquals(0xFFB07C00.toInt(), Palette.attention.toArgb())
        assertEquals(71, (Palette.overlay.alpha * 255).toInt())
        assertEquals(2, Shadow.soft.layers.size)
        assertEquals(120, Motion.durFast)
    }

    @Test
    fun svgPathDataIsReadWithItsShorthand() {
        // Numbers run together, relative commands, and arc flags with no separator.
        val box = SVGPath.parse("M2 2h20v20H2zm4.5-1.5.5.5a1 1 0 011 1").toPath().getBounds()
        assertTrue(abs(box.left - 2) < 0.01f && abs(box.right - 22) < 0.01f, "$box")
        assertTrue(abs(box.top - 0.5f) < 0.01f && abs(box.bottom - 22) < 0.01f, "$box")
        assertEquals(3, SVGPath.points("3 4, 5 6 7,8").size)
    }

    @Test
    fun everyIconHasADrawing() {
        for (icon in LucideIcon.entries) {
            val nodes = icon.nodes.flatMap { it.pathNodes }
            assertTrue(nodes.isNotEmpty(), icon.id)
            val box = nodes.toPath().getBounds()
            assertTrue(box.left >= 0 && box.right <= 24 && box.top >= 0 && box.bottom <= 24, "${icon.id}: $box")
        }
        assertEquals(38, LucideIcon.entries.size)
    }

    @Test
    fun everyAgentTheWebDrawsHasItsLogo() {
        for (agent in listOf("claude", "codex", "grok", "pi")) {
            val logo = assertNotNull(AgentLogoArt.logos[agent], agent)
            assertTrue(logo.paths.all { !it.path.isEmpty }, agent)
        }
    }

    /** SwiftUI's lines for text without a CSS rule, which the Mac's natural text takes. */
    @Test
    @EnabledOnOs(OS.MAC)
    fun textWithoutARuleTakesSwiftUIsLine() {
        assertEquals(NaturalLine(16f, 13f), NaturalLine.of(13f, FontWeight.Normal, false))
        assertEquals(NaturalLine(19f, 15f), NaturalLine.of(16f, FontWeight.SemiBold, false))
    }
}
