package com.junbingao.remotecontrol.win.design.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStackScope
import com.junbingao.remotecontrol.win.design.btn
import com.junbingao.remotecontrol.win.strings.S

/**
 * Hands an overlay's content to the overlay layer while `isPresented` is true, stamped with the
 * moment it opened, and keeps it current: every time the asking view recomposes, the layer draws
 * the content it composed last.
 */
@Composable
internal fun OverlayPresenter(isPresented: Boolean, kind: OverlayKind, onDismiss: () -> Unit, panel: @Composable () -> Unit): OverlayEntry? {
    val registry = LocalOverlayRegistry.current ?: return null
    val dismiss = rememberUpdatedState(onDismiss)
    if (!isPresented) return null
    val entry = remember { OverlayEntry(kind, panel) { dismiss.value() } }
    SideEffect {
        entry.kind = kind
        entry.content = panel
    }
    DisposableEffect(entry) {
        registry.register(entry)
        onDispose { registry.unregister(entry) }
    }
    return entry
}

/**
 * `Modal` (`web/src/components/Modal.tsx`): a dialog over a dimmed page, `width` at most (580 by
 * default), with a title, an optional close button, a body that scrolls past the modal's height
 * and a footer on a rule, its items spread from edge to edge. Escape, a press on the backdrop, or
 * `isPresented` turning false closes it; the first two call `onDismiss`.
 */
@Composable
fun Modal(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    width: Dp = 580.dp,
    showClose: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable VStackScope.() -> Unit,
) {
    OverlayPresenter(isPresented, OverlayKind.Modal(width, showClose), onDismiss) {
        ModalPanel(title, showClose, close = onDismiss, footer = footer, content = content)
    }
}

/**
 * `Drawer`: the right-hand drawer with a title, a subtitle, a close button, a scrolling body and
 * an optional footer — the web's New session drawer.
 */
@Composable
fun Drawer(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable VStackScope.() -> Unit,
) {
    OverlayPresenter(isPresented, OverlayKind.Drawer, onDismiss) {
        DrawerPanel(title, subtitle, close = onDismiss, footer = footer, content = content)
    }
}

/**
 * `ConfirmDialog`: a 440 px modal with the question as its title, one sentence of `.hint` under
 * it, Cancel on the left and the confirming button — danger or primary — on the right, disabled
 * while `busy`.
 */
@Composable
fun ConfirmDialog(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    title: String,
    body: String,
    confirmLabel: String,
    danger: Boolean = false,
    busy: Boolean = false,
    onConfirm: () -> Unit,
) {
    Modal(
        isPresented = isPresented,
        onDismiss = onDismiss,
        title = title,
        width = 440.dp,
        footer = {
            Button(onDismiss, style = btn()) { Text(S.common.cancel) }
            Disabled(busy) {
                Button(onConfirm, style = btn(if (danger) ButtonVariant.danger else ButtonVariant.primary)) { Text(confirmLabel) }
            }
        },
    ) {
        Hint(body)
    }
}

/**
 * A popover panel anchored to this view, for a panel whose opening is not a click on the view
 * itself — the web's `Popover` owns its trigger, and `Popover` is that; this is the same panel for
 * everything else. Escape and a press outside the panel and this view close it, through
 * `onDismiss`.
 */
fun Modifier.anchoredPanel(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    align: PopoverAlign = PopoverAlign.start,
    side: PopoverSide = PopoverSide.bottom,
    content: @Composable () -> Unit,
): Modifier = composed {
    val registry = LocalOverlayRegistry.current
    val bounds = remember { mutableStateOf<Rect?>(null) }
    val entry = OverlayPresenter(isPresented, OverlayKind.Popover(align, side), onDismiss, content)
    val trigger = bounds.value
    if (entry != null) {
        SideEffect {
            val origin = registry?.origin ?: Offset.Zero
            entry.trigger = trigger?.translate(-origin.x, -origin.y)
        }
    }
    onGloballyPositioned { bounds.value = it.boundsInRoot() }
}
