package com.junbingao.remotecontrol.win.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * `.pill`: the 28 px capsule chips and popover triggers are drawn as, with a gap of 6 between
 * what it holds. `.pill.quiet` reads as state rather than as a control: no tint, the secondary
 * ink, almost no padding.
 */
class PillStyle(val quiet: Boolean = false) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val reduceMotion = LocalReduceMotion.current
        val enabled = configuration.isEnabled
        val lit = enabled && !quiet && (configuration.isHovered || configuration.isPressed)
        val fill by animateColorAsState(
            if (quiet) Color.Transparent else if (lit) Palette.surfaceActive else Palette.surfaceMuted,
            Motion.ease(Motion.durFast, reduceMotion),
        )
        val shape = RoundedCornerShape(if (configuration.isFocused) 4.dp else 14.dp)
        val ink = if (!enabled) Palette.inkTertiary else if (quiet) Palette.inkSecondary else Palette.ink
        Row(
            modifier
                .height(28.dp)
                .focusOutline(configuration.isFocused)
                .alpha(if (enabled) 1f else 0.6f)
                .background(fill, shape)
                .padding(horizontal = if (quiet) 2.dp else 11.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides ink, LocalFont provides FontSpec(FontSize.fs13)) {
                configuration.label()
            }
        }
    }
}

/** `.pill` and `.pill.quiet`. */
val pill: ButtonStyle = PillStyle()
val quietPill: ButtonStyle = PillStyle(quiet = true)
