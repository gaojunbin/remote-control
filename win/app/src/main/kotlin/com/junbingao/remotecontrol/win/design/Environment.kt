package com.junbingao.remotecontrol.win.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * What SwiftUI hands down its environment and Compose through composition locals: the ink text
 * and icons take (`foregroundStyle`), the face text without a CSS rule of its own is set in
 * (`font`), and whether the controls below may be used (`disabled`).
 */
val LocalContentColor = compositionLocalOf { Palette.ink }

/** The face of text drawn without a CSS rule, as SwiftUI's `.font(.web(size:weight:))` sets it. */
data class FontSpec(val size: Float = FontSize.fs14, val weight: FontWeight = FontWeight.Normal, val mono: Boolean = false)

val LocalFont = compositionLocalOf { FontSpec() }

/** `isEnabled`: false under a `Disabled`, and every control below reads it. */
val LocalIsEnabled = compositionLocalOf { true }

/** The web's `prefers-reduced-motion`, read from the system once by the window. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * The scenario's stage in a render and null in the app: a view reads it to show, for a picture, a
 * state that takes a click.
 */
val LocalPreviewStage = staticCompositionLocalOf<String?> { null }

/**
 * Whether a focused text field draws its caret. The Mac renderer cannot show the blinking caret,
 * so the renderer here draws none either and the two pictures of a scenario compare.
 */
val LocalShowsCaret = staticCompositionLocalOf { true }

/** `.foregroundStyle(color)` for everything below. */
@Composable
fun WithForeground(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides color, content = content)
}

/** `.font(.web(size:weight:))` for text below that sets no CSS rule of its own. */
@Composable
fun WithFont(size: Float, weight: FontWeight = FontWeight.Normal, mono: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFont provides FontSpec(size, weight, mono), content = content)
}

/** `.disabled(disabled)`: the controls below stop working, and look it where their style says so. */
@Composable
fun Disabled(disabled: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIsEnabled provides (LocalIsEnabled.current && !disabled), content = content)
}
