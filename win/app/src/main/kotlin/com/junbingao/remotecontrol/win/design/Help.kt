package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `.help(text)`: the tooltip the pointer brings up after a moment, drawn as Windows draws its
 * own — a small light box with a hairline edge — below the pointer. An empty text shows nothing,
 * as SwiftUI's does.
 *
 * Like SwiftUI's modifier it leaves the layout alone: the content is measured as it would be
 * without it — a segment told to fill its share still fills it — and the stack around it reads the
 * content's exact height.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Help(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val exact = remember { ExactHeight() }
    val scope = rememberCoroutineScope()
    val tip = remember(scope) { HelpTip(scope) }
    Layout(
        content = {
            content()
            if (tip.shown && text.isNotEmpty()) {
                Popup(TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)).positionProvider(tip.at), onDismissRequest = tip::hide) {
                    Text(
                        text,
                        css(FontSize.fs12),
                        Modifier
                            .background(Palette.surface, RoundedCornerShape(4.dp))
                            .border(1.dp, Palette.line, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        },
        modifier = modifier.exactHeight(exact).then(if (text.isEmpty()) Modifier else Modifier.pointerInput(tip) { tip.follow(this) }),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        exact.fraction = measurables.firstOrNull()?.stackChild?.exact?.fraction ?: 0f
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

/** Whether a help text shows, and where the pointer rests: shown 600 ms after it comes to rest, gone when it leaves or presses. */
private class HelpTip(private val scope: CoroutineScope) {
    var shown by mutableStateOf(false)
        private set
    var at by mutableStateOf(Offset.Zero)
        private set
    private var pending: Job? = null

    suspend fun follow(input: PointerInputScope) = input.awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            when (event.type) {
                PointerEventType.Enter, PointerEventType.Move -> {
                    event.changes.firstOrNull()?.let { at = it.position }
                    if (!shown && !event.buttons.areAnyPressed) show()
                }
                PointerEventType.Exit, PointerEventType.Press -> hide()
            }
        }
    }

    private fun show() {
        if (pending?.isActive == true) return
        pending = scope.launch {
            delay(600)
            shown = true
        }
    }

    fun hide() {
        pending?.cancel()
        pending = null
        shown = false
    }
}
