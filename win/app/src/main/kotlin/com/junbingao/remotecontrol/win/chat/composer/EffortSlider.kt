package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.AgentOption
import com.junbingao.remotecontrol.win.design.BoxShadow
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.boxShadow
import com.junbingao.remotecontrol.win.design.rgb
import com.junbingao.remotecontrol.win.strings.S
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One stop per effort level, in the order the agent lists them. The pill is filled to the thumb and
 * carries one dot per stop, so the number of levels can be read before anything moves; nothing else
 * is drawn, because the word beside the model name is what the thumb is saying. The value is sent
 * only when the thumb is released — the range input's `change`, not its `input` — and an arrow key
 * moves one stop and sends it, as the input's keyboard does.
 *
 * `index` is the live stop, owned by the card so the word above can read it; -1 while the
 * session's effort is none of the agent's.
 */
@Composable
fun EffortSlider(efforts: List<AgentOption>, index: Int, onIndex: (Int) -> Unit, modifier: Modifier = Modifier, onCommit: (Int) -> Unit) {
    val value = max(0, index)
    val last = max(1, efforts.size - 1)
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    Layout(
        content = { Thumb(ringed = focused && keyboard) },
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight.dp)
            .semantics {
                contentDescription = S.composer.effort
                stateDescription = efforts.getOrNull(value)?.label ?: ""
            }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val next = when (event.key) {
                    Key.DirectionLeft, Key.DirectionDown -> max(0, value - 1)
                    Key.DirectionRight, Key.DirectionUp -> min(efforts.size - 1, value + 1)
                    Key.MoveHome -> 0
                    Key.MoveEnd -> efforts.size - 1
                    else -> return@onKeyEvent false
                }
                onIndex(next)
                onCommit(next)
                true
            }
            .focusable()
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(efforts.size) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var x = down.position.x
                    onIndex(stop(x, size.width.toFloat(), efforts.size))
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        x = change.position.x
                        if (!change.pressed) break
                        change.consume()
                        onIndex(stop(x, size.width.toFloat(), efforts.size))
                    }
                    onCommit(stop(x, size.width.toFloat(), efforts.size))
                }
            }
            .drawBehind {
                val inset = thumbInset.dp.toPx()
                val travel = max(0f, size.width - 2 * inset)
                val thumb = inset + value.toFloat() / last * travel
                val radius = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(Palette.surfaceMuted, cornerRadius = radius)
                // Filled from the track's end to the thumb's centre. The lowest stop fills nothing:
                // its cap would only show around the thumb.
                if (value > 0) drawRoundRect(Palette.accent, size = Size(thumb, size.height), cornerRadius = radius)
                for (stop in efforts.indices) {
                    val color = if (value > 0 && stop <= value) Color.White.copy(alpha = 0.55f) else Palette.inkTertiary
                    drawCircle(color, radius = 3.dp.toPx(), center = Offset(inset + stop.toFloat() / last * travel, size.height / 2))
                }
            },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val tall = trackHeight.dp.roundToPx()
        val thumb = measurables.first().measure(Constraints())
        val inset = thumbInset.dp.toPx()
        val travel = max(0f, width - 2 * inset)
        val center = inset + value.toFloat() / last * travel
        layout(width, tall) {
            thumb.place((center - thumb.width / 2f).roundToInt(), (tall - thumb.height) / 2)
        }
    }
}

/** Half a thumb: the span the thumb's centre travels is inset by it. */
private const val thumbInset = 12f
private const val trackHeight = 28f

/** A white disc on a quiet shadow, ringed while the keyboard has it. */
@Composable
private fun Thumb(ringed: Boolean) {
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        if (ringed) Box(Modifier.size(30.dp).background(Color.rgb(17, 17, 17, opacity = 0.22f), CircleShape))
        Box(Modifier.size(24.dp).boxShadow(thumbShadow, CircleShape))
    }
}

private val thumbShadow = BoxShadow(listOf(BoxShadow.Layer(Color.rgb(0, 0, 0, opacity = 0.24f), 0.dp, 1.dp, 3.dp)))

/** The stop under `x` on a slider `width` pixels wide. */
private fun androidx.compose.ui.input.pointer.PointerInputScope.stop(x: Float, width: Float, count: Int): Int {
    val inset = thumbInset.dp.toPx()
    val travel = width - 2 * inset
    if (travel <= 0 || count <= 0) return 0
    val fraction = min(1f, max(0f, (x - inset) / travel))
    return min(count - 1, floor(fraction * max(1, count - 1) + 0.5f).toInt())
}
