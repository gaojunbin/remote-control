package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ChipButtonStyle
import com.junbingao.remotecontrol.android.design.StatusDot
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.attachHint
import com.junbingao.remotecontrol.core.state.canTakeover
import com.junbingao.remotecontrol.core.state.dotTone
import com.junbingao.remotecontrol.core.state.statusLine

/**
 * "Claude Code is working · your message will be queued" and its siblings.
 *
 * Amendment A10: takeover is offered only on a `terminal` session whose agent advertises the
 * capability, and a terminal session the device could attach to says how to make the next run
 * controllable from here.
 */
@Composable
internal fun StatusLine(chat: ChatStore) {
    val model = LocalAppModel.current
    val text = chat.statusLine
    val hint = chat.attachHint
    if (text == null && hint == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Theme.Space.page, vertical = Theme.Space.tight),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.tight),
    ) {
        if (text != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(chat.session.dotTone(online = chat.deviceOnline), size = 6.dp)
                Text(L10n.platform(text), Modifier.weight(1f).testTag("chat.status"), style = SystemFont.footnote, color = Theme.inkSecondary)
                if (chat.canTakeover) {
                    Button(onClick = { model.perform { chat.takeover() } }, modifier = Modifier.testTag("chat.takeover"), style = ChipButtonStyle) {
                        Text(L10n.string("Take over"))
                    }
                }
            }
        }
        if (hint != null) AttachHintLine(hint)
    }
}

/**
 * Amendment A10: what a terminal session would need before this app could control it. One line,
 * under the takeover bar, never a call to action.
 */
@Composable
private fun AttachHintLine(hint: ChatStore.AttachHint) {
    val words = when (hint) {
        ChatStore.AttachHint.installShim -> "Start claude through the remote-control shim to control it from here"
        ChatStore.AttachHint.startDaemon -> "Run rc-client codex setup on the device to attach its Codex sessions"
        ChatStore.AttachHint.installExtension -> "Run rc-client pi setup on the device to attach its pi sessions"
        ChatStore.AttachHint.enableLeader -> "Run rc-client grok setup on the device, then restart Grok"
        ChatStore.AttachHint.restartSession ->
            "This terminal session was started without the attachment; restart it to control it from here"
    }
    Text(L10n.string(words), Modifier.fillMaxWidth().testTag("chat.attachHint"), style = SystemFont.caption, color = Theme.inkSecondary)
}
