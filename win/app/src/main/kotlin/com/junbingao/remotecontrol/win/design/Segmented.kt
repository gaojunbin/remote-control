package com.junbingao.remotecontrol.win.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/** One segment of a `Segmented` control. */
class SegmentOption<Value>(
    val value: Value,
    val disabled: Boolean = false,
    /**
     * What this segment is, in words, for a label that is a glyph or a mark. It names the button
     * for assistive technology and on hover.
     */
    val name: String? = null,
    val label: @Composable () -> Unit,
) {
    constructor(value: Value, label: String, disabled: Boolean = false, name: String? = null) :
        this(value, disabled, name, { Text(label, css(FontSize.fs14), Modifier.fillMaxHeight(), softWrap = false) })
}

/**
 * `web/src/components/Segmented.tsx` / `.segmented`: a muted pill holding segments 3 px apart and
 * inset — sharing a width it is given equally, each its label's width when it takes its own; the
 * chosen one is a white pill with `--shadow-1` and ink text, the others the secondary ink.
 */
@Composable
fun <Value> Segmented(
    value: Value,
    options: List<SegmentOption<Value>>,
    ariaLabel: String,
    modifier: Modifier = Modifier,
    onChange: (Value) -> Unit,
) {
    SegmentRow(modifier.background(Palette.surfaceMuted, CircleShape).padding(3.dp).semantics { contentDescription = ariaLabel }) {
        for (option in options) {
            Help(option.name ?: "") {
                Disabled(option.disabled) {
                    Button(
                        action = { onChange(option.value) },
                        style = SegmentStyle(pressed = option.value == value),
                        accessibilityLabel = option.name,
                        label = option.label,
                    )
                }
            }
        }
    }
}

/**
 * The segments side by side, 3 px apart: given a width, they share it equally, as each segment
 * takes `maxWidth: .infinity`; left to their own width, each is its label's.
 */
@Composable
private fun SegmentRow(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = 3.dp.roundToPx()
        val count = measurables.size
        val placeables = if (constraints.hasBoundedWidth && constraints.minWidth == constraints.maxWidth && count > 0) {
            val share = (constraints.maxWidth - gap * (count - 1)).coerceAtLeast(0)
            measurables.mapIndexed { index, measurable ->
                // Whole pixels, the remainder to the first segments, as the space is shared out.
                val width = share / count + if (index < share % count) 1 else 0
                measurable.measure(Constraints.fixed(width, 32.dp.roundToPx()))
            }
        } else {
            measurables.map { it.measure(Constraints(minHeight = 32.dp.roundToPx(), maxHeight = 32.dp.roundToPx())) }
        }
        val width = placeables.sumOf { it.width } + gap * (count - 1).coerceAtLeast(0)
        layout(constraints.constrainWidth(width), constraints.constrainHeight(32.dp.roundToPx())) {
            var x = 0
            for (placeable in placeables) {
                placeable.place(x, 0)
                x += placeable.width + gap
            }
        }
    }
}

private class SegmentStyle(val pressed: Boolean) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val reduceMotion = LocalReduceMotion.current
        val ink by animateColorAsState(if (pressed) Palette.ink else Palette.inkSecondary, Motion.ease(Motion.durFast, reduceMotion))
        Row(
            modifier
                .height(32.dp)
                .alpha(if (configuration.isEnabled) 1f else 0.4f)
                .then(if (pressed) Modifier.boxShadow(Shadow.one, CircleShape).background(Palette.surface, CircleShape) else Modifier.background(Color.Transparent))
                // Nothing sets the segment's padding, so it is Chrome's for a button: 6 px on
                // either side, which a segment sized by its label keeps (Settings' rows).
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.sp2, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides ink, LocalFont provides FontSpec(FontSize.fs14)) {
                configuration.label()
            }
        }
    }
}
