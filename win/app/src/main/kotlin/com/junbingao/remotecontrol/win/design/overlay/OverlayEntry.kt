package com.junbingao.remotecontrol.win.design.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import java.util.concurrent.atomic.AtomicInteger

/** What kind of overlay an entry is, which decides its backdrop, where its panel goes and how it closes. */
internal sealed interface OverlayKind {
    /** `.overlay` + `.modal`: centred, `width` at most, with a close button in its head or not. */
    data class Modal(val width: Dp, val closeButton: Boolean) : OverlayKind

    /** `.drawer-overlay` + `.drawer`: the right-hand drawer. */
    data object Drawer : OverlayKind

    /** `.popover-panel`, anchored to its trigger. */
    data class Popover(val align: PopoverAlign, val side: PopoverSide) : OverlayKind

    val isPopover: Boolean get() = this is Popover
}

/**
 * One overlay a view asked for, handed to the window's overlay layer, which draws its content in
 * the layer while the view that asked keeps it current: the content and the dismissal are
 * replaced every time the asking view recomposes, so the overlay stays live with its state.
 */
internal class OverlayEntry(kind: OverlayKind, content: @Composable () -> Unit, dismiss: () -> Unit) {
    /** When it opened, which decides what Escape closes: the newest first. */
    val openedAt: Int = OverlayClock.next()
    var kind by mutableStateOf(kind)
    var content by mutableStateOf(content)
    var dismiss: () -> Unit = dismiss

    /** A popover's trigger, in the layer's coordinates (px): a press inside it leaves the popover open. */
    var trigger by mutableStateOf<Rect?>(null)

    /** A popover's panel once it is placed, in the layer's coordinates (px). */
    var panel: Rect? = null
}

/** Hands out the order overlays open in. */
internal object OverlayClock {
    private val last = AtomicInteger()

    fun next(): Int = last.incrementAndGet()
}

/** Whether the popover a control triggers is open: `aria-expanded`, which a trigger style can light itself from. */
val LocalPopoverIsOpen = compositionLocalOf { false }
