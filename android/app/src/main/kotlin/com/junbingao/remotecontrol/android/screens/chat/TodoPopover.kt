package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.icons.SfSymbol
import com.junbingao.remotecontrol.core.protocol.TodoItem
import com.junbingao.remotecontrol.core.protocol.TodoStatus

/** The agent's checklist behind the header's Todos chip: what is done struck through, what runs half filled. */
@Composable
internal fun TodoPopover(items: List<TodoItem>) {
    Column(
        Modifier
            .widthIn(min = 260.dp, max = 360.dp)
            .padding(Theme.Space.medium)
            .testTag("chat.todoList"),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.small),
    ) {
        for (item in items) {
            key(item.id) {
                val completed = item.status == TodoStatus.completed
                Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
                    Icon(symbol(item.status), tint = if (completed) Theme.ink else Theme.resting)
                    Text(
                        item.text,
                        style = if (completed) SystemFont.subheadline.copy(textDecoration = TextDecoration.LineThrough) else SystemFont.subheadline,
                        color = if (completed) Theme.inkSecondary else Theme.ink,
                    )
                }
            }
        }
    }
}

private fun symbol(status: TodoStatus): SfSymbol = when (status) {
    TodoStatus.completed -> Sf.checkmarkCircleFill
    TodoStatus.inProgress -> Sf.circleLefthalfFilled
    else -> Sf.circle
}
