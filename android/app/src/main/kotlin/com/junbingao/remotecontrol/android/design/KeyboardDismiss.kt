package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * Where the text fields on screen are, so a tap can tell a field from its background. Each field
 * registers itself with [textInputRegion]; the regions are in the root's coordinates.
 */
class TextInputRegions {
    private val regions = HashMap<Any, Rect>()

    fun set(owner: Any, bounds: Rect) {
        regions[owner] = bounds
    }

    fun remove(owner: Any) {
        regions.remove(owner)
    }

    fun contains(point: Offset): Boolean = regions.values.any { it.contains(point) }
}

val LocalTextInputRegions = staticCompositionLocalOf { TextInputRegions() }

/** Marks this element as a place a tap is a request to put the cursor, never to close the keyboard. */
fun Modifier.textInputRegion(): Modifier = composed {
    val regions = LocalTextInputRegions.current
    val owner = remember { Any() }
    DisposableEffect(regions) { onDispose { regions.remove(owner) } }
    onGloballyPositioned { regions.set(owner, it.boundsInRoot()) }
}

/**
 * A tap that lands anywhere on this screen other than a text field puts the keyboard away.
 *
 * It watches the touch on its way down to whatever is under it rather than intercepting it: an
 * overlay would have to decide what to let through, and every control it guessed wrong about
 * would need two taps — one to dismiss and one to act. Nothing is consumed, so buttons, menus,
 * disclosures and links keep working on the first tap and the keyboard goes at the same time.
 */
fun Modifier.dismissesKeyboardOnBackgroundTap(): Modifier = composed {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val regions = LocalTextInputRegions.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(regions) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val up = waitForUpOrCancellation(pass = PointerEventPass.Initial) ?: return@awaitEachGesture
                // A drag is scrolling, which the transcript answers on its own terms.
                if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                if (regions.contains(origin + down.position)) return@awaitEachGesture
                endEditing(focus, keyboard)
            }
        }
}

private fun endEditing(focus: FocusManager, keyboard: SoftwareKeyboardController?) {
    focus.clearFocus()
    keyboard?.hide()
}
