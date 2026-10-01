package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * `web/src/styles/base.css`: the canvas behind everything, ink text in the system face at 14 px,
 * ink as the tint every control inherits, and `::selection` for the text fields.
 */
@Composable
fun WebBase(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContentColor provides Palette.ink,
        LocalFont provides FontSpec(FontSize.fs14),
        LocalTextSelectionColors provides TextSelectionColors(handleColor = Palette.ink, backgroundColor = Selection.background),
    ) {
        Box(modifier.background(Palette.canvas)) { content() }
    }
}

/**
 * `:focus-visible`: a 2 px ink outline 2 px outside the control, following the 4 px radius the rule
 * gives it, drawn only while `isVisible`.
 */
fun Modifier.focusOutline(isVisible: Boolean, cornerRadius: Dp = 4.dp): Modifier = drawWithContent {
    drawContent()
    if (isVisible) {
        val out = 4.dp.toPx()
        val width = 2.dp.toPx()
        val radius = (cornerRadius + 4.dp).toPx() - width / 2
        drawRoundRect(
            color = Palette.ink,
            topLeft = Offset(-out + width / 2, -out + width / 2),
            size = Size(size.width + 2 * out - width, size.height + 2 * out - width),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width),
        )
    }
}

/** `::selection`: the tint text selection takes wherever the app draws its own. */
object Selection {
    val background = Color.rgb(17, 17, 17, opacity = 0.12f)
}
