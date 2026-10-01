package com.junbingao.remotecontrol.android.design

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A white card with a hairline border. An edge or a shadow, never both. */
// The colours are the appearance's, read in composition; lint does not count a composable getter.
@SuppressLint("UnnecessaryComposedModifier")
fun Modifier.card(padding: Dp = Theme.Space.medium, radius: Dp = Theme.Radius.card): Modifier = composed {
    val shape = ContinuousShape(radius)
    background(Theme.surface, shape)
        .border(0.5.dp, Theme.border, shape)
        .padding(padding)
}

/**
 * The grouping of last resort: one soft surface, no border, no shadow, no divider around it.
 * Sections are told apart by spacing and type instead.
 */
@SuppressLint("UnnecessaryComposedModifier")
fun Modifier.softSurface(padding: Dp = Theme.Space.medium, radius: Dp = Theme.Radius.card): Modifier = composed {
    background(Theme.surface, ContinuousShape(radius)).padding(padding)
}

/** The page colour, behind everything a screen draws and behind the system bars too. */
@SuppressLint("UnnecessaryComposedModifier")
fun Modifier.pageBackground(): Modifier = composed { background(Theme.canvas) }

/** A translucent strip behind the composer and the footers: the iPhone's `.bar` material. */
@SuppressLint("UnnecessaryComposedModifier")
fun Modifier.barBackground(): Modifier = composed { background(Bar.material) }

/** The `.bar` material, for a surface that is drawn rather than backgrounded. */
object Bar {
    /**
     * UIKit's bar material over the page: the page shows through, lightened, as it does behind
     * the iPhone's bottom bars. Measured over the canvas in the reference screenshots.
     */
    val material: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0xD91C1C1E) else Color(0xB3FFFFFF)
}

/** `plainTextEntry()`: addresses, paths and commands are typed as they are, never re-cased. */
val PlainTextEntry = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
)
