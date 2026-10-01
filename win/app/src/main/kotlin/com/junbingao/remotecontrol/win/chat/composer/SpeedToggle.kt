package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.AgentOption
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonConfiguration
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.focusOutline
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.shared.SessionOptions
import com.junbingao.remotecontrol.win.strings.S

/**
 * A21: standard → each tier the agent lists → standard, one click at a time. The lightning is
 * tinted while a tier is on, the only sign of it in the card.
 */
@Composable
fun SpeedToggle(speeds: List<AgentOption>, current: String?, onSet: (SessionOptions) -> Unit) {
    val tier = speeds.firstOrNull { it.id == current }
    Button(
        {
            val position = speeds.indexOfFirst { it.id == current }.let { if (it < 0) 0 else it + 1 }
            onSet(SessionOptions(speed = SpeedChange(id = speeds.getOrNull(position)?.id)))
        },
        modifier = Modifier.semantics { selected = tier != null },
        style = SpeedToggleStyle(on = tier != null),
        accessibilityLabel = S.composer.speed(tier?.label ?: S.composer.speedStandard),
    ) {
        Icon(LucideIcon.zap, size = 15.dp)
    }
}

/** `.speed-toggle`: a 28 px square on an 8 px radius in the muted tint, the attention colours while a tier is on. */
private class SpeedToggleStyle(private val on: Boolean) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val shape = RoundedCornerShape(if (configuration.isFocused) 4.dp else Radius.sm)
        val lit = configuration.isHovered || configuration.isPressed
        val fill = if (on) Palette.attentionSoft else if (lit) Palette.surfaceActive else Palette.surfaceMuted
        Box(modifier.size(28.dp).focusOutline(configuration.isFocused).background(fill, shape), contentAlignment = Alignment.Center) {
            WithForeground(if (on) Palette.attention else Palette.inkSecondary) { configuration.label() }
        }
    }
}
