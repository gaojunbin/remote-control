package com.junbingao.remotecontrol.win.design.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.IconBtn
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.ThinScrollView
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.VStackScope
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.strings.S

/**
 * A scrolling area as tall as what it holds and no taller, which scrolls only once its container
 * cannot give it that much: `.modal-body` with `overflow-y: auto` and the thin scroll bar.
 */
@Composable
fun FittingScroll(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ThinScrollView(modifier = modifier, content = content)
}

/** `.modal-head` / `.drawer-head`: the 22 px, 600-weight title with the optional close button at the trailing edge. */
@Composable
internal fun OverlayHead(title: String, subtitle: String?, showClose: Boolean, close: () -> Unit, modifier: Modifier = Modifier) {
    HStack(modifier.fillMaxWidth(), spacing = Space.sp4, alignment = Alignment.Top) {
        VStack(Modifier.weight(1f), spacing = 0.dp, alignment = Alignment.Start) {
            Text(title, css(FontSize.fs22, weight = FontWeight.SemiBold, tracking = -0.01f), Modifier.semantics { heading() })
            if (subtitle != null) Hint(subtitle)
        }
        if (showClose) IconBtn(LucideIcon.x, size = 16.dp, label = S.common.close, action = close)
    }
}

/**
 * `web/src/components/Modal.tsx`'s markup: the head, the body that scrolls past the modal's
 * height, and the footer on a 1 px `--line` rule.
 */
@Composable
internal fun ModalPanel(
    title: String?,
    showClose: Boolean,
    close: () -> Unit,
    footer: (@Composable () -> Unit)?,
    content: @Composable VStackScope.() -> Unit,
) {
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        if (title != null) {
            OverlayHead(title, null, showClose, close, Modifier.padding(start = Space.sp6, end = Space.sp6, top = Space.sp6))
        }
        FittingScroll(Modifier.weight(1f, fill = false)) {
            // `.modal-body` is a block: what it holds stacks with no gap.
            VStack(
                Modifier.fillMaxWidth().padding(start = Space.sp6, end = Space.sp6, top = Space.sp4, bottom = Space.sp6),
                spacing = 0.dp,
                alignment = Alignment.Start,
                content = content,
            )
        }
        if (footer != null) {
            // The rule is the footer's own top border, and takes its pixel.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
            SpaceBetween(Space.sp3, Modifier.fillMaxWidth().padding(vertical = Space.sp4, horizontal = Space.sp6), footer)
        }
    }
}

/**
 * `Drawer` in `Modal.tsx`: the head with its title, subtitle and close button, the body that
 * fills and scrolls with 20 px between its sections, and the optional footer.
 */
@Composable
internal fun DrawerPanel(
    title: String,
    subtitle: String?,
    close: () -> Unit,
    footer: (@Composable () -> Unit)?,
    content: @Composable VStackScope.() -> Unit,
) {
    VStack(Modifier.fillMaxSize(), spacing = 0.dp, alignment = Alignment.Start) {
        OverlayHead(
            title, subtitle, showClose = true, close = close,
            modifier = Modifier.padding(start = Space.sp6, end = Space.sp6, top = Space.sp6, bottom = Space.sp4),
        )
        ThinScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
            VStack(
                Modifier.fillMaxWidth().padding(start = Space.sp6, end = Space.sp6, bottom = Space.sp6),
                spacing = Space.sp5,
                alignment = Alignment.Start,
                content = content,
            )
        }
        if (footer != null) {
            Box(Modifier.fillMaxWidth().padding(start = Space.sp6, end = Space.sp6, top = Space.sp4, bottom = Space.sp6)) { footer() }
        }
    }
}

/**
 * `display: flex; justify-content: space-between`: the first item at the leading edge, the last
 * at the trailing edge, the rest spread between, each vertically centred, at least
 * `minimumSpacing` apart. A single item sits at the leading edge.
 */
@Composable
fun SpaceBetween(minimumSpacing: Dp = 0.dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val items = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val gap = minimumSpacing.roundToPx()
        val natural = items.sumOf { it.width } + gap * (items.size - 1).coerceAtLeast(0)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else natural
        val height = items.maxOfOrNull { it.height } ?: 0
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            val total = items.sumOf { it.width }
            val gaps = (items.size - 1).coerceAtLeast(1)
            val spacing = if (items.size > 1) maxOf(gap, (width - total) / gaps) else 0
            var x = 0
            for (item in items) {
                item.place(x, (height - item.height) / 2)
                x += item.width + spacing
            }
        }
    }
}
