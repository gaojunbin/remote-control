package com.junbingao.remotecontrol.win.chat.blocks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.chat.support.ChatText
import com.junbingao.remotecontrol.win.chat.support.chatBox
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.strings.S

/**
 * `web/src/features/chat/blocks/AgentMessageRow.tsx` — A34: words another agent put into the
 * conversation, a teammate's report or a background task's notification. Nobody typed them, so
 * they sit on the left with the agent's own output, as a muted block on the quiet surface
 * captioned "from another agent" (`docs/DESIGN.md` § "The timeline"). The device has already
 * reduced the text to who reported and what they said (A30), so the row prints exactly what it
 * was given.
 */
@Composable
fun AgentMessageRow(text: String) {
    VStack(
        Modifier
            .fillMaxWidth()
            .chatBox(radius = Radius.md, background = Palette.surfaceSunken, clips = false)
            .padding(vertical = 10.dp, horizontal = Space.sp4),
        spacing = 0.dp,
        alignment = Alignment.Start,
    ) {
        Text(S.chat.fromAgent, css(FontSize.fs11, lineHeight = 1.55f), Modifier.padding(bottom = 2.dp), color = Palette.inkTertiary)
        ChatText(ChatPreText.display(text), css(FontSize.fs14, lineHeight = 1.55f), color = Palette.inkSecondary)
    }
}
