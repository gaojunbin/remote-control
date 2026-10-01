package com.junbingao.remotecontrol.win.sessions.controls

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.ButtonConfiguration
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.FontSpec
import com.junbingao.remotecontrol.win.design.LocalContentColor
import com.junbingao.remotecontrol.win.design.LocalFont
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.focusOutline
import com.junbingao.remotecontrol.win.design.hex

/**
 * `.btn.small` stretched to 32 px, as `.picker-new-row .btn` sets it so the new folder row's
 * buttons stand as tall as its field. The foundation's `BtnStyle` has no height of its own to give;
 * the rest is its: a pill of 13 px, 500-weight type, the tint one step darker under the pointer,
 * the default faded and the primary grey while disabled.
 */
internal class RowBtnStyle(private val primary: Boolean) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val enabled = configuration.isEnabled
        val lit = enabled && (configuration.isHovered || configuration.isPressed)
        val fill by animateColorAsState(fill(lit, enabled), Motion.ease(Motion.durFast, LocalReduceMotion.current))
        val shape = RoundedCornerShape(if (configuration.isFocused) 4.dp else HEIGHT / 2)
        Row(
            modifier
                .height(HEIGHT)
                .focusOutline(configuration.isFocused)
                .alpha(if (enabled || primary) 1f else 0.45f)
                .background(fill, shape)
                .padding(horizontal = Space.sp3),
            horizontalArrangement = Arrangement.spacedBy(Space.sp2, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides if (primary) Palette.inkInverse else Palette.ink,
                LocalFont provides FontSpec(FontSize.fs13, FontWeight.Medium),
            ) { configuration.label() }
        }
    }

    private fun fill(lit: Boolean, enabled: Boolean): Color {
        if (!primary) return if (lit) Palette.surfaceActive else Palette.surfaceMuted
        if (!enabled) return Color.hex(0xB9B9B4)
        return if (lit) Color.hex(0x262626) else Palette.ink
    }

    private companion object {
        val HEIGHT = 32.dp
    }
}
