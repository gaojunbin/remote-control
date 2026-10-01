package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.placeholderInk
import com.junbingao.remotecontrol.win.voice.VoiceControls
import kotlin.math.max

/**
 * `.composer-field` and `.composer`: the box the words are typed in, with the controls at its
 * trailing edge — or, while dictation runs, the voice controls on a row of their own under the
 * words — and the slash panel standing on it.
 */
@Composable
fun ComposerBox(composer: ComposerModel, sendMenuOpen: Boolean, modifier: Modifier = Modifier) {
    val gates = composer.gates
    var focused by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    val shape = RoundedCornerShape(Radius.lg)
    val listening = composer.voiceBusy
    val edge by animateColorAsState(border(disabled = gates.disabled, focused = focused), Motion.ease(Motion.durFast, reduceMotion), label = "edge")
    StandingOn(panel = { ComposerPanel(composer) }, modifier = modifier) {
        // The field is the layout's first child in both forms, so it is the same text field before,
        // during and after a dictation.
        ComposerBoxLayout(
            wrapped = listening,
            modifier = Modifier
                .background(if (gates.disabled) Palette.surfaceSunken else Palette.surface, shape)
                .border(1.dp, edge, shape)
                .dropsFiles(composer)
                .padding(start = Space.sp4 + 1.dp, top = Space.sp2 + 1.dp, end = Space.sp2 + 1.dp, bottom = Space.sp2 + 1.dp),
        ) {
            Field(composer, disabled = gates.disabled) { focused = it }
            if (listening) {
                VoiceControls(composer.voice, composer.slot) { composer.voice.done() }
            } else {
                ComposerButtons(composer, sendMenuOpen)
            }
        }
    }
}

/** The field, with the placeholder where the words would start while there are none. */
@Composable
private fun Field(composer: ComposerModel, disabled: Boolean, onFocusChange: (Boolean) -> Unit) {
    Box {
        ComposerField(composer, text = composer.text, disabled = disabled, readOnly = composer.returning, onFocusChange = onFocusChange)
        if (composer.text.isEmpty()) {
            Text(
                composer.placeholder,
                css(FontSize.fs14),
                Modifier.padding(top = ComposerFieldText.padding.dp).clearAndSetSemantics {},
                color = placeholderInk,
            )
        }
    }
}

/** `.composer:focus-within` turns the edge ink; `.composer.disabled`, the quiet line, whatever else holds. */
private fun border(disabled: Boolean, focused: Boolean): Color = when {
    disabled -> Palette.line
    focused -> Palette.ink
    else -> Palette.lineStrong
}

/**
 * A27: the panel while the draft is a slash and a partial name, or the hint once the first word is
 * complete — standing 8 px above the box, its full width, never moving the field.
 */
@Composable
private fun ComposerPanel(composer: ComposerModel) {
    if (composer.panelOpen) {
        CommandPanel(
            rows = composer.panelRows,
            highlight = composer.highlightIndex,
            running = composer.gates.running,
            onHighlight = { composer.highlight = it },
            onTake = { composer.take(it) },
        )
    } else {
        composer.commandMatch?.let { CommandHint(it.command) }
    }
}

/**
 * Files dropped on the box attach, or are refused whole: a path is never typed in their place.
 * Where the composer takes no files, the drop is refused.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private fun Modifier.dropsFiles(composer: ComposerModel): Modifier = composed {
    val target = remember(composer) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                if (!composer.acceptsFiles) return false
                val sources = ComposerTextView.files(event.awtTransferable) ?: return false
                composer.attach(sources)
                return true
            }
        }
    }
    dragAndDropTarget(shouldStartDragAndDrop = { ComposerTextView.holdsFiles(it.awtTransferable) }, target = target)
}

/**
 * A line of no height along the box's top edge carries the panel, which stands on it 8 px up and
 * grows upward from there, as wide as the box. It takes no room of its own.
 */
@Composable
private fun StandingOn(panel: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout({ Box { content() }; Box { panel() } }, modifier) { measurables, constraints ->
        val box = measurables[0].measure(constraints)
        val above = measurables[1].measure(Constraints(minWidth = box.width, maxWidth = box.width))
        val gap = Space.sp2.roundToPx()
        layout(box.width, box.height) {
            box.place(0, 0)
            above.place(0, -above.height - gap, zIndex = 1f)
        }
    }
}

/**
 * `.composer` is a row aligned to the bottom with 8 between its children; `.composer.listening`
 * wraps, and the voice controls take a row of their own under the field, 8 below it.
 */
@Composable
fun ComposerBoxLayout(wrapped: Boolean, modifier: Modifier = Modifier, spacing: Dp = Space.sp2, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        if (measurables.size != 2) return@Layout layout(0, 0) {}
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 400.dp.roundToPx()
        val gap = spacing.roundToPx()
        if (wrapped) {
            val field = measurables[0].measure(Constraints.fixedWidth(width))
            val controls = measurables[1].measure(Constraints.fixedWidth(width))
            layout(width, field.height + gap + controls.height) {
                field.place(0, 0)
                controls.place(0, field.height + gap)
            }
        } else {
            val trailing = measurables[1].measure(Constraints())
            val field = measurables[0].measure(Constraints.fixedWidth(max(0, width - trailing.width - gap)))
            val height = max(field.height, trailing.height)
            layout(width, height) {
                field.place(0, height - field.height)
                trailing.place(width - trailing.width, height - trailing.height)
            }
        }
    }
}
