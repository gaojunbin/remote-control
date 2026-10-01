package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** The stacks lay out as SwiftUI's do: in fractions of a point, each view on the pixel its exact place rounds to. */
class StackTests {
    private fun frames(density: Float = 2f, content: @Composable (track: (String) -> Modifier) -> Unit): Map<String, IntRect> {
        val frames = mutableMapOf<String, IntRect>()
        val scene = ImageComposeScene(1000, 1000, Density(density)) {
            content { name ->
                Modifier.onGloballyPositioned {
                    val origin = it.positionInRoot()
                    frames[name] = IntRect(origin.x.toInt(), origin.y.toInt(), origin.x.toInt() + it.size.width, origin.y.toInt() + it.size.height)
                }
            }
        }
        repeat(3) { scene.render(it * 16_000_000L) }
        scene.close()
        return frames
    }

    /** Three 12 px captions at `line-height: 1.4` are 16.8 points each: at 2× the third starts at 67 px, not 68. */
    @Test
    fun aVStackKeepsTheFractionsItsChildrenReport() {
        val frames = frames { track ->
            VStack(track("stack"), spacing = 0.dp, alignment = Alignment.Start) {
                for (index in 0 until 3) Text("Caption", css(FontSize.fs12, lineHeight = 1.4f), track("caption$index"))
                Box(track("after").size(10.dp))
            }
        }
        assertEquals(listOf(0, 34, 67), (0 until 3).map { frames.getValue("caption$it").top })
        assertEquals(101, frames.getValue("after").top)
        assertEquals(121, frames.getValue("stack").height)
    }

    /** A stack inside a stack reports its own exact height, so the fractions add up across the two. */
    @Test
    fun nestedStacksAddTheirFractionsUp() {
        val frames = frames { track ->
            VStack(spacing = 0.dp, alignment = Alignment.Start) {
                for (index in 0 until 3) {
                    VStack(spacing = 0.dp, alignment = Alignment.Start) {
                        Text("Caption", css(FontSize.fs12, lineHeight = 1.4f))
                    }
                }
                Box(track("after").size(10.dp))
            }
        }
        assertEquals(101, frames.getValue("after").top)
    }

    /** `Alignment.FirstTextBaseline` lines the first baselines up; a view without text stands on its bottom edge. */
    @Test
    fun anHStackOnTheFirstBaselineLinesTheTextsUp() {
        val frames = frames(density = 1f) { track ->
            HStack(spacing = 8.dp, alignment = Alignment.FirstTextBaseline) {
                Text("Title", css(FontSize.fs22), track("title"))
                Text("meta", css(FontSize.fs13), track("meta"))
                Box(track("icon").size(16.dp))
            }
        }
        val title = frames.getValue("title").top + TextStyle(FontSize.fs22).baseline.toInt()
        val meta = frames.getValue("meta").top + TextStyle(FontSize.fs13).baseline.toInt()
        assertEquals(title, meta)
        assertEquals(title, frames.getValue("icon").bottom)
    }

    /** SwiftUI's `Spacer()` is a weighted child: it takes what the others leave. */
    @Test
    fun aWeightedChildTakesTheRoomLeft() {
        val frames = frames(density = 1f) { track ->
            HStack(Modifier.width(300.dp)) {
                Box(Modifier.size(100.dp))
                Spacer(track("spacer").weight(1f))
                Box(track("end").size(50.dp))
            }
        }
        assertEquals(134, frames.getValue("spacer").width)
        assertEquals(250, frames.getValue("end").left)
    }
}
