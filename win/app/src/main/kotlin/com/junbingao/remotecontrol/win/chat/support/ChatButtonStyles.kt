package com.junbingao.remotecontrol.win.chat.support

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.ButtonConfiguration
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.FontSpec
import com.junbingao.remotecontrol.win.design.LocalContentColor
import com.junbingao.remotecontrol.win.design.LocalFont
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.TextStyle
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.focusOutline
import com.junbingao.remotecontrol.win.design.hex
import androidx.compose.ui.graphics.Color

/**
 * `.link-btn`: no border, no fill, no padding, the surrounding size, and an underline 2 points
 * below the text, in the ink — or in the surrounding ink where the rule says `color: inherit`.
 */
class ChatLinkButtonStyle(val inheritsColor: Boolean = false) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        Box(modifier.focusOutline(configuration.isFocused).alpha(if (configuration.isEnabled) 1f else 0.5f)) {
            if (inheritsColor) configuration.label() else WithForeground(Palette.ink) { configuration.label() }
        }
    }
}

val chatLink: ButtonStyle = ChatLinkButtonStyle()
val chatInheritedLink: ButtonStyle = ChatLinkButtonStyle(inheritsColor = true)

/**
 * The underlined words of a `.link-btn` or a Markdown link: the underline sits `offset` below the
 * baseline, one point thick, in the text's own ink.
 */
@Composable
fun ChatUnderlinedText(text: String, style: TextStyle, offset: Dp = 2.dp, modifier: Modifier = Modifier) {
    val ink = LocalContentColor.current
    Text(
        text,
        style,
        modifier.drawWithContent {
            drawContent()
            drawRect(ink, Offset(0f, (style.baseline.dp + offset).toPx()), Size(size.width, 1.dp.toPx()))
        },
    )
}

/**
 * Whether the pointer is over the bare button a label is drawn in, for a label that lights up
 * under the pointer as the Mac's `.onHover` lights it.
 */
val LocalChatButtonHovered = compositionLocalOf { false }

/**
 * A `<button>` the stylesheet draws itself: no press tint, and no dimming when disabled, because
 * `button { color: inherit }` keeps the ink. The label hears whether the pointer is over it.
 */
val chatBare: ButtonStyle = ButtonStyle { configuration, modifier ->
    Box(modifier) {
        CompositionLocalProvider(LocalChatButtonHovered provides configuration.isHovered) { configuration.label() }
    }
}

/**
 * `.btn.small.primary` stretched across a flex column, as the resume form's Set is: the
 * foundation's `.btn` keeps its own width, and a column item in the browser takes the column's.
 */
object ChatStretchedPrimaryStyle : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        Box(
            modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(fill(configuration), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides Palette.inkInverse,
                LocalFont provides FontSpec(FontSize.fs13, FontWeight.Medium),
            ) { configuration.label() }
        }
    }

    private fun fill(configuration: ButtonConfiguration): Color = when {
        !configuration.isEnabled -> Color.hex(0xB9B9B4)
        configuration.isHovered || configuration.isPressed -> Color.hex(0x262626)
        else -> Palette.ink
    }
}
