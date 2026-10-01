package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.RootView
import com.junbingao.remotecontrol.win.design.overlay.ConfirmDialog
import com.junbingao.remotecontrol.win.design.overlay.Modal
import com.junbingao.remotecontrol.win.design.overlay.OverlayRegistry
import com.junbingao.remotecontrol.win.design.overlay.Popover
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The overlay layer's rules, in a scene: what Escape closes, what a press outside closes, what blurs the page. */
class OverlayTests {
    private class Harness(content: @androidx.compose.runtime.Composable () -> Unit) {
        val overlays = OverlayRegistry()
        private val scene = ImageComposeScene(1280, 860, Density(1f)) { RootView(overlays) { content() } }
        private var time = 0L

        fun frames(count: Int = 4) {
            repeat(count) {
                time += 16_000_000L
                scene.render(time)
            }
        }

        fun press(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            frames()
        }

        fun close() = scene.close()
    }

    @Test
    fun escapeClosesTheNewestOverlayAndOnlyThatOne() {
        var modal by mutableStateOf(true)
        var confirm by mutableStateOf(false)
        val harness = Harness {
            Box(Modifier.fillMaxSize()) {
                Modal(isPresented = modal, onDismiss = { modal = false }, title = "Rename") { Text("Body") }
                ConfirmDialog(confirm, { confirm = false }, "Sure?", "Body", "Yes") {}
            }
        }
        harness.frames()
        confirm = true
        harness.frames()
        assertTrue(harness.overlays.isBlocking)
        assertTrue(harness.overlays.dismissNewest())
        harness.frames()
        assertFalse(confirm)
        assertTrue(modal)
        assertTrue(harness.overlays.dismissNewest())
        harness.frames()
        assertFalse(modal)
        assertFalse(harness.overlays.dismissNewest())
        assertTrue(harness.overlays.isEmpty)
        harness.close()
    }

    @Test
    fun aPressOutsideClosesAPopoverAndAPressOnTheBackdropClosesAModal() {
        var opened = 0
        var modal by mutableStateOf(false)
        val harness = Harness {
            Box(Modifier.fillMaxSize()) {
                Popover(initiallyOpen = true, label = { Text("Model") }) { close ->
                    opened++
                    MenuItemRow("Pick") { close() }
                }
                Modal(isPresented = modal, onDismiss = { modal = false }, width = 400.dp) {
                    Text("Body")
                }
            }
        }
        harness.frames()
        assertTrue(opened > 0)
        assertFalse(harness.overlays.isEmpty)
        // Inside the panel, in its inset beside the row: it stays open. The panel hangs 6 px under
        // the trigger and keeps 8 px to the window's edge.
        harness.press(10f, 36f)
        assertFalse(harness.overlays.isEmpty, "a press inside the panel")
        // Anywhere else: it closes.
        harness.press(900f, 600f)
        assertTrue(harness.overlays.isEmpty, "a press outside")
        modal = true
        harness.frames()
        assertFalse(harness.overlays.isEmpty)
        // The panel is centred; its corner of the window is the backdrop.
        harness.press(20f, 840f)
        assertFalse(modal, "a press on the backdrop")
        harness.close()
    }
}
