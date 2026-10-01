package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * What a button's style is handed, as SwiftUI hands a `ButtonStyle`: the label to draw, and
 * whether the button is pressed, under the pointer, focused and enabled.
 */
class ButtonConfiguration(
    val label: @Composable () -> Unit,
    val isPressed: Boolean,
    val isHovered: Boolean,
    val isFocused: Boolean,
    val isEnabled: Boolean,
)

/**
 * A button's look: the Mac's `ButtonStyle`s — `.btn`, `.pill`, `.iconBtn`, the menu trigger — so a
 * control that draws its own trigger, a popover, can take whichever style the feature gives it.
 * The style draws the body and puts `modifier` on its outermost layout: the caller's modifier
 * and the button's own press, hover and focus handling.
 */
fun interface ButtonStyle {
    @Composable
    fun Body(configuration: ButtonConfiguration, modifier: Modifier)
}

/** The label as it is, pressed or not, enabled or not. */
val PlainButtonStyle = ButtonStyle { configuration, modifier -> Box(modifier) { configuration.label() } }

/**
 * SwiftUI's `Button` with a style: `action` on a click, the pointer a hand while it may be
 * pressed, and `accessibilityLabel` as its name where the label is a glyph. `Disabled` above it
 * stops it working.
 */
@Composable
fun Button(
    action: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = PlainButtonStyle,
    accessibilityLabel: String? = null,
    label: @Composable () -> Unit,
) {
    val enabled = LocalIsEnabled.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    // `:focus-visible`: the ring is for focus the keyboard moved, never for a click.
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val interaction = modifier
        .then(if (accessibilityLabel != null) Modifier.semantics { contentDescription = accessibilityLabel } else Modifier)
        .hoverable(source, enabled)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = action)
    style.Body(ButtonConfiguration(label, pressed && enabled, hovered && enabled, focused && keyboard, enabled), interaction)
}
