package com.junbingao.remotecontrol.win.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.IconBtn
import com.junbingao.remotecontrol.win.design.LayoutSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.layout.LocalTrafficLightInset
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.terminal-head`: the back arrow, the device's name with the status line under it, and Close at
 * the trailing edge, on a 60 px white strip over a `--line` rule. On Windows the strip sits under
 * the system title bar, so the arrow starts at the web's padding where the Mac's starts after its
 * traffic lights.
 */
@Composable
fun TerminalHead(title: String, onBack: () -> Unit, onClose: () -> Unit, status: @Composable () -> Unit) {
    val trafficLightInset = LocalTrafficLightInset.current
    VStack(Modifier.fillMaxWidth().background(Palette.surface), spacing = 0.dp) {
        HStack(
            Modifier
                .fillMaxWidth()
                // `min-height: var(--header-h)` holds the rule too (border-box).
                .heightIn(min = LayoutSize.headerH - 1.dp)
                .padding(vertical = Space.sp2)
                .padding(start = maxOf(Space.sp4, trafficLightInset), end = Space.sp4),
            spacing = Space.sp3,
        ) {
            IconBtn(LucideIcon.arrowLeft, size = 17.dp, label = S.terminal.back, action = onBack)
            VStack(Modifier.weight(1f), spacing = 0.dp, alignment = Alignment.Start) {
                Text(
                    title,
                    css(FontSize.fs15, weight = FontWeight.SemiBold, lineHeight = 1.4f, tracking = -0.01f),
                    Modifier.semantics { heading() },
                    lineLimit = 1,
                )
                status()
            }
            Btn(S.common.close, size = ButtonSize.small, action = onClose)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
    }
}
