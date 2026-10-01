package com.junbingao.remotecontrol.android.system

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.LocalAppearance
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.design.widestLine

/** One segment: its words, or an image alone (an agent's logo) named for a screen reader. */
data class Segment(val title: String, val image: ImageVector? = null, val tag: String? = null)

/**
 * `Picker(...).pickerStyle(.segmented)` as iOS 26 draws it: a grey capsule 32 points tall with a
 * white capsule thumb inset two points under the chosen segment, its words in the semibold weight
 * and the others regular, 13 points. [fill] stretches the segments across the width offered, as
 * the new-session sheet's agent control does; otherwise each is as wide as its widest label needs
 * in the semibold weight, whichever is chosen, so choosing another never moves the control.
 */
@Composable
fun SegmentedControl(
    segments: List<Segment>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    tag: String? = null,
) {
    val still = LocalAppearance.current.reduceMotion
    val widest = widestLine(segments.filter { it.image == null }.map { it.title }, SystemFont.footnote.weight(FontWeight.SemiBold))
    val natural = maxOf(widest, if (segments.any { it.image != null }) SegmentedMetrics.imageSide else 0.dp) + SegmentedMetrics.labelPadding * 2
    Box(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .height(SegmentedMetrics.height)
            .background(SegmentedMetrics.track, CapsuleShape)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        EqualSegments(segments.size, fill, natural) { segment ->
            val chosen = segment == selected
            val item = segments[segment]
            Box(
                Modifier
                    .fillMaxHeight()
                    .selectable(
                        selected = chosen,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                    ) { onSelect(segment) }
                    .then(if (item.image != null) Modifier.semantics { contentDescription = item.title } else Modifier)
                    .then(if (item.tag != null) Modifier.testTag(item.tag) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (chosen) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .padding(SegmentedMetrics.inset)
                            .shadow(1.dp, CapsuleShape, ambientColor = Color(0x0A000000), spotColor = Color(0x1A000000))
                            .background(SegmentedMetrics.thumb, CapsuleShape),
                    )
                }
                if (item.image != null) {
                    Image(item.image, contentDescription = null, colorFilter = ColorFilter.tint(Theme.ink), modifier = Modifier.size(SegmentedMetrics.imageSide))
                } else {
                    Text(
                        item.title,
                        style = if (chosen) SystemFont.footnote.weight(FontWeight.SemiBold) else SystemFont.footnote,
                        color = Theme.ink,
                        alignment = TextAlign.Center,
                        lineLimit = 1,
                    )
                }
            }
        }
    }
}

/**
 * Segments of one width: [natural], or an equal share of the width when the control fills it —
 * UIKit's `apportionsSegmentWidthsByContent` left off, as SwiftUI leaves it.
 */
@Composable
private fun EqualSegments(count: Int, fill: Boolean, natural: Dp, segment: @Composable (Int) -> Unit) {
    Layout(content = { repeat(count) { segment(it) } }) { measurables, constraints ->
        val height = constraints.maxHeight
        val each = if (fill && constraints.hasBoundedWidth) constraints.maxWidth / count.coerceAtLeast(1) else natural.roundToPx()
        val placeables = measurables.map { it.measure(androidx.compose.ui.unit.Constraints.fixed(each, height)) }
        layout(each * count, height) {
            placeables.forEachIndexed { index, placeable -> placeable.place(each * index, 0) }
        }
    }
}

/** The segmented control's measurements, from the iPhone 17 reference screenshots. */
object SegmentedMetrics {
    val height = 32.dp
    val inset = 2.dp

    /**
     * Either side of the widest label set in the semibold weight: Language's English and 中文 are
     * 63 points a segment, Detail's 简约 and 详细 42.7.
     */
    val labelPadding = 8.5.dp

    /**
     * An image segment draws its image at the image's own size, as UIKit does: the agents' logos
     * are 24-point assets, not the 17-point mark a row sets beside its words.
     */
    val imageSide = 24.dp

    val track: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0xFF2C2C2E) else Color(0xFFEEEEEF)

    val thumb: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0xFF636366) else Color.White
}
