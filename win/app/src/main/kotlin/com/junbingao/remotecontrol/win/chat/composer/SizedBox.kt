package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import com.junbingao.remotecontrol.win.shared.LabelPair

/**
 * `web/src/features/chat/SizedBox.tsx`: a box as wide as the widest thing it can ever hold. The
 * model chip and the card's name row stay the width of the widest model-and-effort pair the agent
 * offers, so choosing a level or a model never shifts what sits beside them (`docs/DESIGN.md` §
 * "The composer"). Every alternative is laid out and not drawn, and none of them is read by
 * assistive technology — measured, never guessed.
 */
@Composable
fun SizedBox(
    alternatives: List<LabelPair>,
    modifier: Modifier = Modifier,
    alternative: @Composable (LabelPair) -> Unit,
    shown: @Composable () -> Unit,
) {
    Layout(
        content = {
            for ((index, pair) in alternatives.withIndex()) key(index) { Box { alternative(pair) } }
            Box { shown() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = placeables.maxOf { it.width }
        val height = placeables.maxOf { it.height }
        layout(width, height) {
            // A `ZStack(alignment: .leading)`: the one drawn sits at the leading edge, centred down.
            val visible = placeables.last()
            visible.place(0, (height - visible.height) / 2)
        }
    }
}
