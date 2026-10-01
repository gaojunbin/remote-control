package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.monospacedDigit
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.ScrollTail

/**
 * The way back down, centred at the foot of the timeline above the message field. It is on screen
 * whenever the reader is not at the bottom, and carries what arrived while they were away. A
 * floating control has to lift off the transcript, and the design spends that budget on a soft
 * shadow rather than on an edge.
 */
@Composable
internal fun JumpToLatestButton(chat: ChatStore, action: () -> Unit) {
    val badge = ScrollTail.badge(updates = chat.updatesWhileAway)
    val shadow = Theme.ink.copy(alpha = 0.12f)
    Button(
        onClick = action,
        modifier = Modifier
            .semantics {
                contentDescription = L10n.string("Jump to latest")
                stateDescription = ScrollTail.spokenBadge(updates = chat.updatesWhileAway) ?: ""
            }
            .testTag("chat.jumpToLatest"),
    ) {
        Foreground(Theme.ink) {
            Row(
                Modifier
                    .dropShadow(CapsuleShape, Shadow(radius = 6.dp, color = shadow, offset = DpOffset(0.dp, 2.dp)))
                    .background(Theme.surface, CapsuleShape)
                    .sizeIn(minWidth = Theme.Touch.minimum, minHeight = Theme.Touch.minimum)
                    .padding(horizontal = if (badge == null) 0.dp else Theme.Space.small),
                horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Sf.arrowDown, font = SystemFont.footnote.weight(FontWeight.SemiBold))
                if (badge != null) Text(badge, style = SystemFont.footnote.weight(FontWeight.Medium).monospacedDigit())
            }
        }
    }
}
