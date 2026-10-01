package com.junbingao.remotecontrol.win.design.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.ZLayer
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter

/**
 * The one overlay layer (`docs/DESIGN.md` § "The Windows app", after the Mac's **Overlays are the
 * web's**): popovers, menus, modals and the drawer are drawn in the window, above everything,
 * with the web's surfaces, shadows and placement — never as system popups, dialogs or menus — and
 * they close as the web's do: Escape (the window's key map calls `dismissNewest`), a press
 * outside, or the control that opened them.
 *
 * A view asks for an overlay with `Modal`, `Drawer`, `ConfirmDialog`, `Modifier.anchoredPanel` or
 * a `Popover`; the request is handed to this layer, which draws it, while the view that asked
 * keeps its content current. A modal or the drawer blurs everything under it — the page, and any
 * modal or drawer opened before it — and every popover is drawn above them all.
 *
 * The content of an overlay is drawn here and reads this layer's composition locals, not those
 * where it was asked for: pass anything a feature keeps in its own locals in explicitly.
 */
@Composable
fun OverlayHost(registry: OverlayRegistry = remember { OverlayRegistry() }, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    CompositionLocalProvider(LocalOverlayRegistry provides registry) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { registry.origin = it.positionInRoot() }
                .pointerInput(registry) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                event.changes.firstOrNull()?.let { registry.pointerDown(it.position) }
                            }
                        }
                    }
                },
        ) {
            // `.overlay`'s `backdrop-filter: blur(2px)` over the page.
            Box(Modifier.fillMaxSize().overlayBlur(registry.isBlocking && !reduceMotion)) { content() }
            OverlayLayer(registry)
        }
    }
}

/** Every overlay, the newest modal on top and every popover above them all (`--z-popover` clears `--z-overlay`). */
@Composable
private fun OverlayLayer(registry: OverlayRegistry) {
    val ordered = registry.entries.sortedWith(compareBy({ it.kind.isPopover }, { it.openedAt }))
    val reduceMotion = LocalReduceMotion.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = androidx.compose.ui.geometry.Size(maxWidth.value, maxHeight.value)
        for ((index, entry) in ordered.withIndex()) {
            // A modal or the drawer opened later lies over this one, and its backdrop blurs it.
            val covered = ordered.drop(index + 1).any { !it.kind.isPopover }
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(if (entry.kind.isPopover) ZLayer.popover else ZLayer.overlay)
                    .overlayBlur(covered && !entry.kind.isPopover && !reduceMotion),
            ) {
                androidx.compose.runtime.key(entry) {
                    when (val kind = entry.kind) {
                        is OverlayKind.Modal -> ModalFrame(kind.width, startsInField = !kind.closeButton, viewport, entry.dismiss) { entry.content() }
                        OverlayKind.Drawer -> DrawerFrame(viewport, entry.dismiss) { entry.content() }
                        is OverlayKind.Popover -> PopoverFrame(entry, kind.align, kind.side, viewport) { entry.content() }
                    }
                }
            }
        }
    }
}

/**
 * The blur a modal's backdrop puts over what it covers: the web's `backdrop-filter: blur(2px)` as
 * the Mac draws it (SwiftUI's blur of radius 1), which Skia matches with a Gaussian of 0.94 px of
 * deviation per CSS px — measured on the two renderers' pictures of the same page.
 */
internal fun Modifier.overlayBlur(on: Boolean): Modifier = if (!on) this else graphicsLayer {
    val sigma = 0.94.dp.toPx()
    renderEffect = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL).asComposeRenderEffect()
}
