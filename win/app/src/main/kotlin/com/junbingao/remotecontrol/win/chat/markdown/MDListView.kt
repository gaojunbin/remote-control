package com.junbingao.remotecontrol.win.chat.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.FirstTextBaseline
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.SystemFace
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.TextStyle
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.roundHalfUp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * `ul` and `ol` in `.md`: 20 points of gutter, each marker on its item's first baseline and set in
 * the item's own type — "•", then "◦" inside another list, then "▪", and "1." for an ordered
 * list — ending where the item starts, as the browser's outside `::marker` does.
 */
@Composable
fun MDListView(list: MDList, modifier: Modifier = Modifier) {
    VStack(modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        for ((index, item) in list.items.withIndex()) {
            val gap = if (index == 0) 0f else max(list.items[index - 1].bottom, item.top)
            HStack(Modifier.fillMaxWidth().padding(top = gap.dp), spacing = 0.dp, alignment = Alignment.FirstTextBaseline) {
                Box(Modifier.width(MDListMarkers.gutter.dp), contentAlignment = Alignment.TopEnd) {
                    if (list.ordered) {
                        MDNumberMarker("${list.start + index}.")
                    } else {
                        MDSymbolMarker(
                            when (list.depth) {
                                0 -> MDSymbolMarker.Symbol.disc
                                1 -> MDSymbolMarker.Symbol.circle
                                else -> MDSymbolMarker.Symbol.square
                            },
                        )
                    }
                }
                MDStackView(item, Modifier.weight(1f))
            }
        }
    }
}

object MDListMarkers {
    const val gutter = 20f
}

/**
 * A numbered item's `::marker`: its number and a period in the item's type, followed by the space
 * the browser sets after it, ending where the item starts and overflowing the gutter to the left
 * when it is wider.
 */
@Composable
private fun MDNumberMarker(text: String) {
    val ink = LocalMdInk.current
    Text(
        text,
        css(MDBuilder.body, lineHeight = 1.65f),
        Modifier.layout { measurable, _ ->
            val placeable = measurable.measure(Constraints())
            val gutter = MDListMarkers.gutter.dp.roundToPx()
            val space = MDSymbolMarker.space * density
            layout(gutter, placeable.height) { placeable.place((gutter - space - placeable.width).roundToInt(), 0) }
        },
        color = ink,
        softWrap = false,
    )
}

/**
 * A bullet as Chrome paints `disc`, `circle` and `square`: a shape, not a glyph, a little under
 * half the font's ascent across, set on the first line by the ascent and 15 points before the
 * item's text (Blink's `ListMarker`).
 */
@Composable
private fun MDSymbolMarker(symbol: MDSymbolMarker.Symbol) {
    val ink = LocalMdInk.current
    val style = TextStyle(size = MDBuilder.body, lineHeight = 1.65f)
    val geometry = MDSymbolMarker.geometry
    Text(
        " ",
        style,
        Modifier
            .width(MDListMarkers.gutter.dp)
            .drawBehind {
                val side = geometry.side * density
                val origin = Offset((MDListMarkers.gutter - geometry.inset) * density, (style.baseline - geometry.rise) * density)
                when (symbol) {
                    MDSymbolMarker.Symbol.disc -> drawCircle(ink, side / 2, origin + Offset(side / 2, side / 2))
                    MDSymbolMarker.Symbol.circle ->
                        drawCircle(ink, side / 2 - 0.5f * density, origin + Offset(side / 2, side / 2), style = Stroke(width = density))
                    MDSymbolMarker.Symbol.square -> drawRect(ink, origin, Size(side, side))
                }
            },
        color = androidx.compose.ui.graphics.Color.Transparent,
        softWrap = false,
    )
}

object MDSymbolMarker {
    enum class Symbol { disc, circle, square }

    data class Geometry(val side: Float, val rise: Float, val inset: Float)

    /**
     * From the font's rounded ascent: the bullet's side, how far above the baseline its top is,
     * and how far before the item's text it starts. The marker box starts `offset + 7 + 1` before
     * the text, where `offset` is two thirds of the ascent in whole points, and the bullet one
     * point into it.
     */
    val geometry: Geometry by lazy {
        val ascent = roundHalfUp(SystemFace.face(MDBuilder.body, FontWeight.Normal, mono = false).metrics(MDBuilder.body).first)
        val twoThirds = ascent * 2 / 3
        val side = (twoThirds + 1) / 2
        val top = 3 * (ascent - twoThirds) / 2
        val inset = (floor(twoThirds) + 7 + 1) - 1
        Geometry(side = side, rise = ascent - top, inset = inset)
    }

    /** The width of a space at the body size, which ends every marker, with the face's own tracking. */
    val space: Float by lazy {
        val face = SystemFace.face(MDBuilder.body, FontWeight.Normal, mono = false)
        org.jetbrains.skia.Font(face.typeface, MDBuilder.body).measureTextWidth(" ") + face.tracking
    }
}

/** `.md blockquote`: a 2-point rule on the left, 12 points of padding, the quieter ink. */
@Composable
fun MDQuoteView(stack: MDStack, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalMdInk provides Palette.inkSecondary) {
        MDStackView(
            stack,
            modifier
                .drawBehind { drawRect(Palette.line, size = Size(2.dp.toPx(), size.height)) }
                .padding(start = Space.sp3 + 2.dp),
        )
    }
}
