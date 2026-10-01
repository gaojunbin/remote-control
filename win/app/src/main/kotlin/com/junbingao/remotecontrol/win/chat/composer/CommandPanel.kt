package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.Command
import com.junbingao.remotecontrol.win.design.FirstTextBaseline
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Shadow
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.ThinScrollView
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.boxShadow
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.shared.SlashCommands
import com.junbingao.remotecontrol.win.strings.S
import kotlin.math.ceil
import kotlin.math.min

/**
 * A27 — the terminal's `/` menu, above the composer (`CommandMenu.tsx`). One row per command:
 * `/name` in the monospaced face, the description after it, the argument placeholder at the trailing
 * edge when the command takes one; group headers only when the agent distinguishes more than one
 * group. It is not a popover — nothing opened it, a keystroke did — so it is drawn in the
 * composer's own flow, over the field, and the field keeps the focus: typing goes on filtering, and
 * the keys reach the rows through the field.
 */
@Composable
fun CommandPanel(
    rows: List<Command>,
    highlight: Int,
    /** A27: a command waits for the turn, so the rows dim and the footer says so. */
    running: Boolean,
    onHighlight: (Int) -> Unit,
    onTake: (Command) -> Unit,
) {
    val sections = SlashCommands.sections(rows)
    val headers = sections.count { it.group != null }
    // The list's own height: the rows and whatever group headers it draws.
    val contentHeight = rowHeight * rows.size + headers * headerHeight
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    CommandSurface(Modifier.semantics { contentDescription = S.commands.menu }) {
        VStack(Modifier.padding(Space.sp1), spacing = 0.dp, alignment = Alignment.Start) {
            ThinScrollView(modifier = Modifier.fillMaxWidth().height(min(contentHeight, rowHeight * visibleRows).dp), state = scroll) {
                VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
                    for (section in sections) {
                        section.group?.let { group ->
                            Text(
                                group.uppercase(),
                                css(FontSize.fs11, weight = FontWeight.Medium, tracking = 0.04f),
                                Modifier.padding(start = 10.dp, top = Space.sp2, end = 10.dp, bottom = 4.dp).clearAndSetSemantics {},
                                color = Palette.inkTertiary,
                            )
                        }
                        for (command in section.items) {
                            val index = rows.indexOf(command)
                            CommandRow(command, highlighted = index == highlight, running = running, onHover = { onHighlight(index) }, onTake = { onTake(command) })
                        }
                    }
                }
            }
            if (running) {
                Text(
                    S.commands.whileRunning,
                    css(FontSize.fs12),
                    Modifier.padding(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 4.dp),
                    color = Palette.inkTertiary,
                )
            }
        }
    }
    // Arrowing past the eighth row brings the row into view.
    LaunchedEffect(highlight) {
        if (highlight !in rows.indices) return@LaunchedEffect
        val top = rowTop(sections, rows[highlight]) * density.density
        val bottom = top + rowHeight * density.density
        when {
            top < scroll.value -> scroll.scrollTo(top.toInt())
            bottom > scroll.value + scroll.viewportSize -> scroll.scrollTo(ceil(bottom - scroll.viewportSize).toInt())
        }
    }
}

/** Eight rows, then it scrolls — the list is read, not scrolled through. */
private const val rowHeight = 34f
private const val visibleRows = 8

/** `.command-group-name`: 11 px type on a 16.5 px line, 8 above and 4 below. */
private const val headerHeight = 16.5f + 8f + 4f

/** Where a row starts in the list, below the rows and headers before it. */
private fun rowTop(sections: List<SlashCommands.Section>, command: Command): Float {
    var top = 0f
    for (section in sections) {
        if (section.group != null) top += headerHeight
        for (item in section.items) {
            if (item == command) return top
            top += rowHeight
        }
    }
    return top
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CommandRow(command: Command, highlighted: Boolean, running: Boolean, onHover: () -> Unit, onTake: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(rowHeight.dp)
            .alpha(if (running) 0.45f else 1f)
            .background(if (highlighted) Palette.surfaceHover else Color.Transparent, RoundedCornerShape(Radius.sm))
            .onPointerEvent(PointerEventType.Enter) { onHover() }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onTake)
            .clearAndSetSemantics {
                contentDescription = S.commands.rowLabel(command.name, command.description)
                role = Role.Button
                selected = highlighted
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        CommandRowText(command)
    }
}

/**
 * A27 — what a complete first word says about the rest of the line: the command and where its
 * argument goes. It stands where the panel stood, so the field does not jump when the panel closes
 * on the space after the name.
 */
@Composable
fun CommandHint(command: Command) {
    CommandSurface {
        Box(Modifier.fillMaxWidth().padding(vertical = Space.sp2, horizontal = 10.dp).semantics(mergeDescendants = true) {}) {
            CommandRowText(command)
        }
    }
}

/** `/name`, the description, the argument: set on one baseline, and at the top of the row the way a flex row aligned on baselines sets them. */
@Composable
private fun CommandRowText(command: Command) {
    HStack(Modifier.fillMaxWidth(), spacing = Space.sp2, alignment = Alignment.FirstTextBaseline) {
        Text("/${command.name}", css(FontSize.fs13, mono = true), color = Palette.ink, softWrap = false)
        Text(command.description, css(FontSize.fs13), Modifier.weight(1f), color = Palette.inkSecondary, lineLimit = 1)
        command.argument?.takeIf { it.isNotEmpty() }?.let {
            Text(it, css(FontSize.fs12), color = Palette.inkTertiary, softWrap = false)
        }
    }
}

/** The panel's surface: the popover's white, radius and shadow, rising three pixels into place as it appears (`rc-command-pop`). */
@Composable
private fun CommandSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.ease(Motion.durFast, reduceMotion)) }
    val shape = RoundedCornerShape(Radius.md)
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = shown.value
                translationY = (1 - shown.value) * 3.dp.toPx()
            }
            .boxShadow(Shadow.pop, shape)
            .background(Palette.surface, shape)
            .then(modifier),
    ) {
        content()
    }
}
