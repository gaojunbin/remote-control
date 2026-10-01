package com.junbingao.remotecontrol.win.chat.timeline

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import com.junbingao.remotecontrol.win.design.StackFrame
import com.junbingao.remotecontrol.win.design.fraction
import com.junbingao.remotecontrol.win.design.stackChild
import com.junbingao.remotecontrol.win.design.stackFrame
import kotlin.math.roundToInt

/**
 * Where the Mac's lazy stack would put the transcript's rows, which the lazy list cannot: SwiftUI
 * stacks a 41.7-point bubble 41.7 points tall and the row under it starts there, where a lazy
 * list rounds every row to the pixel and the rows below it drift. Each row reports its exact
 * height as it is measured (`Row`), and once the list has laid its rows out, each is told how far
 * its content is from where the Mac's would be: by whole pixels it moves there, and the rest, a
 * fraction of a pixel, is the offset its text rounds its lines from (`StackFrame`), so a line box
 * lands on the point the Mac's lands on.
 *
 * Positions are the Mac's from the top while the list shows its first row and from the end while
 * it shows its last: a lazy list knows the rows it has laid out, and those are the ones the place
 * of the rows on screen depends on.
 */
class TranscriptExact {
    internal val frame = StackFrame(vertical = true, origin = true)
    private val heights = HashMap<Any, Float>()
    /** Read as the rows are placed, so a row moves again when the list works out another place for it. */
    private val shifts = mutableStateMapOf<Any, Int>()

    /** The list's modifier: the space the rows' text rounds its lines in, and the pass that tells each row where it is. */
    fun modifier(info: () -> LazyListLayoutInfo): Modifier = Modifier
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                place(coordinates, info())
                placeable.place(0, 0)
            }
        }
        .stackFrame(frame)

    private fun place(own: LayoutCoordinates?, info: LazyListLayoutInfo) {
        val items = info.visibleItemsInfo
        if (own == null || items.isEmpty()) return
        val spacing = info.mainAxisItemSpacing.toFloat()
        val before = info.beforeContentPadding.toFloat()
        val first = items.first()
        val last = items.last()
        fun height(item: LazyListItemInfo): Float = heights[item.key] ?: item.size.toFloat()
        // The exact top of every row on screen, from the rows on either side of it.
        val tops = FloatArray(items.size)
        when {
            first.index == 0 && first.offset >= 0 -> {
                var top = before + first.offset
                for ((index, item) in items.withIndex()) {
                    tops[index] = top
                    top += height(item) + spacing
                }
            }
            last.index == info.totalItemsCount - 1 && last.offset + last.size + info.afterContentPadding <= info.viewportEndOffset -> {
                var bottom = before + last.offset + last.size
                for (index in items.indices.reversed()) {
                    val item = items[index]
                    tops[index] = bottom - height(item)
                    bottom = tops[index] - spacing
                }
            }
            else -> {
                var top = before + first.offset
                for ((index, item) in items.withIndex()) {
                    tops[index] = top
                    top += height(item) + spacing
                }
            }
        }
        val spans = items.mapIndexed { index, item ->
            val slot = before + item.offset
            val delta = tops[index] - slot
            val shift = delta.roundToInt()
            if (shifts[item.key] != shift) shifts[item.key] = shift
            val start = (slot + shift).roundToInt()
            StackFrame.Span(start, start + item.size, delta - shift)
        }
        frame.placed(own, spans.sortedBy { it.start })
    }

    /** One row of the list: it reports its exact height, and its content moves by the whole pixels the list worked out for it. */
    @Composable
    fun Item(key: Any, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
        Layout(content, modifier) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints) }
            val width = placeables.maxOfOrNull { it.width } ?: 0
            val height = placeables.maxOfOrNull { it.height } ?: 0
            val fraction = if (measurables.size == 1) measurables[0].stackChild.fraction else 0f
            heights[key] = height + fraction
            layout(width, height) {
                val shift = shifts[key] ?: 0
                for (placeable in placeables) placeable.place(0, shift)
            }
        }
    }
}
