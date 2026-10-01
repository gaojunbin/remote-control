package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The line box, the thin scroll bars and the overlay layer's rules, measured in a scene. */
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
