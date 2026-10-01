package com.junbingao.remotecontrol.win.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon

/**
 * `.icon-btn`: a 28 px square with an 8 px radius, no border and no fill until the pointer is
 * over it, the icon in the secondary ink.
 */
class IconBtnStyle(val side: Dp = 28.dp) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val reduceMotion = LocalReduceMotion.current
        val lit = configuration.isEnabled && (configuration.isHovered || configuration.isPressed)
        val ink by animateColorAsState(if (lit) Palette.ink else Palette.inkSecondary, Motion.ease(Motion.durFast, reduceMotion))
        val fill by animateColorAsState(if (lit) Palette.surfaceActive else Color.Transparent, Motion.ease(Motion.durFast, reduceMotion))
        val shape = RoundedCornerShape(if (configuration.isFocused) 4.dp else Radius.sm)
        Box(
            modifier.size(side).focusOutline(configuration.isFocused).background(fill, shape),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides ink) { configuration.label() }
        }
    }
}

/** `.iconBtn`. */
val iconBtn: ButtonStyle = IconBtnStyle()

/**
 * An icon button with the accessible name the web gives it as `aria-label`. The web sets no
 * `title` on these, so there is no tooltip here either.
 */
@Composable
fun IconBtn(icon: LucideIcon, size: Dp = 16.dp, label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    Button(action, modifier, style = iconBtn, accessibilityLabel = label) { Icon(icon, size = size) }
}
