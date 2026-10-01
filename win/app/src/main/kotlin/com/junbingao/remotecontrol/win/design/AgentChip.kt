package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.agent-chip`: which agent a session runs. One quiet tint for every agent, no border, one per
 * row: the logo and the name are what tell them apart, never a colour per vendor.
 */
@Composable
fun AgentChip(agent: String, modifier: Modifier = Modifier) {
    WithForeground(Palette.inkSecondary) {
        HStack(
            modifier.height(18.dp).background(Palette.surfaceMuted, CircleShape).padding(horizontal = 7.dp),
            spacing = 5.dp,
        ) {
            AgentLogo(agent, size = FontSize.fs11)
            Text(S.agentLabel(agent), css(FontSize.fs11, weight = FontWeight.Medium), Modifier.fillMaxHeight(), softWrap = false, lineLimit = 1)
        }
    }
}
