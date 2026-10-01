package com.junbingao.remotecontrol.android.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * SwiftUI's `.foregroundStyle(_:)` and `.font(_:)` as they reach a view's children: text and
 * symbols that set neither take the nearest one set above them, so a button can colour and size
 * its label once, as on the iPhone.
 */
val LocalForeground = compositionLocalOf { Color.Unspecified }
val LocalFont = compositionLocalOf { SystemFont.body }

/** `.foregroundStyle(color)` and, optionally, `.font(font)` over [content]. */
@Composable
fun Foreground(color: Color, font: TextStyle? = null, content: @Composable () -> Unit) {
    if (font == null) {
        CompositionLocalProvider(LocalForeground provides color, content = content)
    } else {
        CompositionLocalProvider(LocalForeground provides color, LocalFont provides font, content = content)
    }
}

/** `.font(font)` over [content]. */
@Composable
fun FontScope(font: TextStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFont provides font, content = content)
}
