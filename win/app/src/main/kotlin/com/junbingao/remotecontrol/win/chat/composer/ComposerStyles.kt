package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonConfiguration
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalIsEnabled
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.TextStyle
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.focusOutline

/**
 * `.composer-bottom .pill`: the control row's chips and popover triggers — a pill two pixels
 * shorter than the rest of the app's, in 12 px type.
 */
object ComposerChipStyle : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val reduceMotion = LocalReduceMotion.current
        val enabled = configuration.isEnabled
        val lit = enabled && (configuration.isHovered || configuration.isPressed)
        val fill by animateColorAsState(if (lit) Palette.surfaceActive else Palette.surfaceMuted, Motion.ease(Motion.durFast, reduceMotion), label = "chip")
        val shape = RoundedCornerShape(if (configuration.isFocused) 4.dp else 13.dp)
        HStack(
            modifier
                .height(26.dp)
                .focusOutline(configuration.isFocused)
                .alpha(if (enabled) 1f else 0.6f)
                .background(fill, shape)
                .padding(horizontal = 11.dp),
            spacing = 6.dp,
        ) {
            WithForeground(if (enabled) Palette.ink else Palette.inkTertiary) { configuration.label() }
        }
    }
}

/** `.pill.send-alt`: the ⋯ beside Send, a 28 px circle in the pill's tint holding the one character in the secondary ink. */
object SendAltStyle : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val reduceMotion = LocalReduceMotion.current
        val lit = configuration.isHovered || configuration.isPressed
        val fill by animateColorAsState(if (lit) Palette.surfaceActive else Palette.surfaceMuted, Motion.ease(Motion.durFast, reduceMotion), label = "send-alt")
        val shape = RoundedCornerShape(if (configuration.isFocused) 4.dp else 14.dp)
        Box(
            modifier.size(28.dp).focusOutline(configuration.isFocused).background(fill, shape),
            contentAlignment = Alignment.Center,
        ) {
            WithForeground(Palette.inkSecondary) { configuration.label() }
        }
    }
}

/**
 * `.link-btn`: words that act, underlined two pixels below their baseline, in the ink of the line
 * they sit in unless told otherwise; disabled, the tertiary ink.
 */
@Composable
fun LinkButton(title: String, size: Float, color: Color = Palette.ink, modifier: Modifier = Modifier, action: () -> Unit) {
    val enabled = LocalIsEnabled.current
    Button(action, modifier) { LinkText(title, size, if (enabled) color else Palette.inkTertiary) }
}

/** The words of a `.link-btn`, with the underline where the browser draws `text-underline-offset: 2px`: its thickness below a gap under the baseline. */
@Composable
fun LinkText(title: String, size: Float, color: Color) {
    val style = TextStyle(size = size)
    Text(
        title,
        style,
        Modifier.drawWithContent {
            drawContent()
            drawRect(color, Offset(0f, (style.baseline + 2).dp.toPx()), Size(this.size.width, 1.dp.toPx()))
        },
        color = color,
        softWrap = false,
    )
}
