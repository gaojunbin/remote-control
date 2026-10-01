package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.state.RelativeTime

/** "Thought for 12s", collapsed until asked for. */
@Composable
internal fun ThinkingRow(text: String, durationMS: Int?, done: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val title = when {
        !done -> L10n.string("Thinking")
        durationMS == null || durationMS <= 0 -> L10n.string("Thought about it")
        else -> L10n.string("Thought for %@", RelativeTime.duration(milliseconds = durationMS))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
        Button(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Theme.Touch.minimum)
                .testTag("chat.thinking"),
        ) {
            Foreground(Theme.inkSecondary) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (expanded) Sf.chevronDown else Sf.chevronRight, font = SystemFont.caption2)
                    Text(title, style = SystemFont.footnote)
                }
            }
        }
        if (expanded && text.isNotEmpty()) {
            SelectionContainer {
                Text(text, Modifier.padding(start = Theme.Space.medium), style = SystemFont.footnote, color = Theme.inkSecondary)
            }
        }
    }
}
