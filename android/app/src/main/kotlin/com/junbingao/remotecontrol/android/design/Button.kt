package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role

/** What a button style is told while it draws: SwiftUI's `ButtonStyleConfiguration`. */
data class ButtonConfiguration(val isPressed: Boolean, val isEnabled: Boolean)

/**
 * SwiftUI's `ButtonStyle`: one place that decides how a label looks as a button, pressed or not,
 * so a screen says `style = PrimaryButtonStyle()` where the iPhone says `.buttonStyle(...)`.
 */
fun interface ButtonStyle {
    @Composable
    fun Body(configuration: ButtonConfiguration, label: @Composable () -> Unit)
}

/**
 * SwiftUI's `Button`: the whole label is the target, it acts on release, and its style draws it.
 * There is no ripple — the iPhone has none; each style says what pressing looks like.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ButtonStyle = PlainButtonStyle,
    label: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        style.Body(ButtonConfiguration(isPressed = pressed, isEnabled = enabled), label)
    }
}

/** `.buttonStyle(.plain)`: the label as it is, dimmed while it is held. */
val PlainButtonStyle = ButtonStyle { configuration, label ->
    Box(Modifier.alpha(if (configuration.isPressed) 0.35f else 1f)) { label() }
}

/**
 * The iPhone's default button in a toolbar, an alert or a list: the label in the tint (the
 * app's accent, `.tint(Theme.accent)` at the root), dimmed while held and greyed when disabled.
 */
val BorderlessButtonStyle = ButtonStyle { configuration, label ->
    val ink = if (configuration.isEnabled) Theme.accent else SystemColor.tertiaryLabel
    Foreground(ink) {
        Box(Modifier.alpha(if (configuration.isPressed) 0.35f else 1f)) { label() }
    }
}
