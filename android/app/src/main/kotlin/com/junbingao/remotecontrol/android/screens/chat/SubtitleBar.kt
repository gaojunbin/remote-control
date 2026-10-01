package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ChipButtonStyle
import com.junbingao.remotecontrol.android.design.CodeText
import com.junbingao.remotecontrol.android.design.Label
import com.junbingao.remotecontrol.android.design.StatusLabel
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.Truncation
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionUsage
import com.junbingao.remotecontrol.core.protocol.TodoItem
import com.junbingao.remotecontrol.core.state.RelativeTime
import com.junbingao.remotecontrol.core.state.dotTone

/**
 * `device · working directory · branch`, plus the todo and usage chips. They live here rather
 * than in the navigation bar so neither ever truncates. The state word gives way last and the
 * working directory first, as the iPhone's layout priorities have it. [drawsTodos] is false at the
 * Simple level, which leaves the checklist out altogether.
 */
@Composable
internal fun SubtitleBar(session: Session, device: Device?, elapsed: String, todos: List<TodoItem>, drawsTodos: Boolean) {
    val hairline = Theme.hairline
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.canvas)
            .drawBehind {
                val y = size.height - 0.25.dp.toPx()
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
            }
            .padding(horizontal = Theme.Space.page)
            .padding(bottom = Theme.Space.small),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.tight),
    ) {
        PriorityRow(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, spacing = Theme.Space.tight) {
            Box(Modifier.layoutPriority(2)) {
                StatusLabel(session.dotTone(online = device?.online ?: false), session.statusLabel)
            }
            Dot()
            val name = device?.name ?: session.deviceID
            Text(name, Modifier.layoutPriority(1).hugsLines(name, Theme.Text.caption, lineLimit = 1), style = Theme.Text.caption, color = Theme.inkSecondary, lineLimit = 1)
            Dot()
            CodeText(session.cwd, Modifier.flexible().hugsLines(session.cwd, Theme.Text.metaMono, lineLimit = 1, truncation = Truncation.head), font = Theme.Text.metaMono)
            session.git?.branch?.let { branch ->
                Dot()
                Text(branch, Modifier.layoutPriority(1).hugsLines(branch, Theme.Text.metaMono, lineLimit = 1), style = Theme.Text.metaMono, color = Theme.inkSecondary, lineLimit = 1)
            }
            RowSpacer()
        }
        val counts = session.todos
        val usage = session.usage
        if ((drawsTodos && counts != null) || usage != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
                if (drawsTodos && counts != null && counts.total > 0) TodosChip(counts.done, counts.total, todos)
                if (usage != null && usage.totalTokens > 0) {
                    Text(
                        SubtitleWords.metrics(usage, elapsed),
                        Modifier
                            .semantics { contentDescription = L10n.string("%lld tokens used", usage.totalTokens) }
                            .testTag("chat.usage"),
                        style = Theme.Text.caption,
                        color = Theme.inkSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Dot() {
    Text("·", Modifier.flexible(), style = Theme.Text.caption, color = Theme.inkSecondary, lineLimit = 1)
}

@Composable
private fun TodosChip(done: Int, total: Int, todos: List<TodoItem>) {
    var showsTodos by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    Button(
        onClick = { showsTodos = true },
        modifier = Modifier
            .onGloballyPositioned { anchor = it.boundsInRoot() }
            .testTag("chat.todos"),
        style = ChipButtonStyle,
    ) {
        Label(L10n.string("Todos %lld/%lld", done, total), Sf.checklist, font = SystemFont.caption)
    }
    Popover(showsTodos, onDismiss = { showsTodos = false }, anchor = anchor, edge = PopoverEdge.below) {
        TodoPopover(todos)
    }
}

internal object SubtitleWords {
    fun metrics(usage: SessionUsage, elapsed: String): String {
        val tokens = RelativeTime.compactCount(usage.totalTokens)
        return if (elapsed.isEmpty()) tokens else "$tokens · $elapsed"
    }
}
