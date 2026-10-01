package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The line box, help texts, segments and the thin scroll bars, measured in a scene. */
class LayoutTests {
    private fun scene(width: Int, height: Int, density: Float = 1f, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(density), content = content)
        scene.render()
        scene.render()
        scene.close()
    }

    /** A CSS rule's box is `line-height × font-size` tall, and a wrapped block one box per line. */
    @Test
    fun aTextTakesTheBrowsersRoom() {
        var one = 0
        var three = 0
        scene(400, 400, density = 2f) {
            Column {
                Box(Modifier.onGloballyPositioned { one = it.size.height }) { Text("One line", css(FontSize.fs13)) }
                Box(Modifier.size(60.dp, 400.dp)) {
                    Box(Modifier.onGloballyPositioned { three = it.size.height }) { Text("aaaa bbbb cccc", css(FontSize.fs14)) }
                }
            }
        }
        assertEquals(39, one)
        assertEquals(3 * 42, three)
    }

    /** A line centred in a fixed height lands on a whole CSS px: 4.25 px down is drawn at 4. */
    @Test
    fun aCentredLineLandsOnAWholePixel() {
        var baseline = 0
        scene(200, 200, density = 2f) {
            Box(Modifier.height(28.dp)) {
                Box(
                    Modifier.onGloballyPositioned {
                        baseline = it[androidx.compose.ui.layout.FirstBaseline]
                    },
                ) {
                    Text("Small", css(FontSize.fs13), Modifier.height(28.dp))
                }
            }
        }
        // round((28 − 19.5) ÷ 2) = 4, and the 13 px baseline sits 14 into its box.
        assertEquals((4 + 14) * 2, baseline)
    }

    /**
     * A line's glyphs sit on the browser's baseline, where Skia draws them and not where Compose
     * reports them: 15 px on a 1.4 line reports its baseline at 32.17 and is drawn at 33, 16 px
     * semibold on a 1.4 line reports 34.69 and is drawn at 34.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    fun aLineIsDrawnOnTheBrowsersBaseline() {
        val styles = listOf(
            css(FontSize.fs15, lineHeight = 1.4f),
            css(FontSize.fs16, FontWeight.SemiBold, lineHeight = 1.4f),
            css(FontSize.fs14, lineHeight = 1.65f),
            css(FontSize.fs12, lineHeight = 1.6f, mono = true),
            css(FontSize.fs13),
        )
        for (style in styles) {
            val scene = ImageComposeScene(200, 100, Density(2f)) { Text("H", style, color = Color.Black) }
            val pixels = scene.render().toComposeImageBitmap().toPixelMap()
            scene.close()
            val stem = (0 until pixels.width).maxBy { x -> (0 until pixels.height).count { y -> pixels[x, y].alpha > 0.5f } }
            val foot = (0 until pixels.height).last { y -> pixels[stem, y].alpha > 0.5f }
            assertEquals((style.baseline * 2).roundToInt(), foot + 1, "$style")
        }
    }

    /** A help text leaves the layout alone: what it wraps is measured as it would be without it. */
    @Test
    fun aHelpTextLeavesTheLayoutAlone() {
        var width = 0
        scene(400, 200) {
            Box(Modifier.size(120.dp, 32.dp), propagateMinConstraints = true) {
                Help("Thinking") { Box(Modifier.onGloballyPositioned { width = it.size.width }) }
            }
        }
        assertEquals(120, width)
    }

    /** A segment named for its glyph still shares the control's width, its label centred in its share. */
    @Test
    fun aNamedSegmentFillsItsShare() {
        var left = 0f
        scene(400, 200) {
            Segmented(1, listOf(SegmentOption(1, name = "One") { Box(Modifier.size(10.dp).onGloballyPositioned { left = it.positionInRoot().x }) }, SegmentOption(2, "Two")), "Pick", Modifier.width(300.dp)) {}
        }
        // 3 px of inset, then a share of (294 − 3) ÷ 2 = 146 whose middle is 76.
        assertEquals(76f, left + 5f, 1f)
    }

    @Test
    fun anAxisReadsAsTheScrollerDoes() {
        val long = ScrollAxis(content = 720f, visible = 200f, offset = 130f)
        assertTrue(long.overflows && long.maxOffset == 520f)
        assertEquals(200f / 720, long.knobProportion, 0.0001f)
        assertEquals(0.25f, long.value, 0.0001f)
        assertTrue(long.offset(0.5f) == 260f && long.offset(1.5f) == 520f && long.offset(-1f) == 0f)
        // WebKit's page: seven eighths of what shows, or all of it but 40 px.
        assertEquals(175f, long.page)
        assertEquals(560f, ScrollAxis(1000f, 600f, 0f).page)
        val short = ScrollAxis(200f, 200f, 0f)
        assertTrue(!short.overflows && short.knobProportion == 1f && short.value == 0f)
    }

    /** The thin scroll bar is drawn over the content, so the content keeps the pane's whole width. */
    @Test
    fun theContentKeepsThePanesWidth() {
        var width = 0
        scene(300, 200) {
            ThinScrollView(modifier = Modifier.size(300.dp, 200.dp)) {
                Column(Modifier.fillMaxWidth().onGloballyPositioned { width = it.size.width }) {
                    repeat(40) { Box(Modifier.fillMaxWidth().height(24.dp)) { Text("Row $it") } }
                }
            }
        }
        assertEquals(300 - ScrollThin.gutter.value.toInt(), width)
        assertFalse(ScrollThin.gutter.value > 0)
    }
}
