package com.junbingao.remotecontrol.win.chat.timeline

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.junbingao.remotecontrol.win.chat.support.ChatText
import com.junbingao.remotecontrol.win.chat.support.ChatUnderlinedText
import com.junbingao.remotecontrol.win.chat.support.chatInheritedLink
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.Dot
import com.junbingao.remotecontrol.win.design.DotStyle
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.TextStyle
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.status-line`: a dot and a sentence, 13 points, in the tone's ink, and a Take over link at its
 * end where the words invite one.
 */
@Composable
fun StatusLineView(line: StatusLineModel, onTakeover: () -> Unit, modifier: Modifier = Modifier) {
    WithForeground(ink(line.tone)) {
        HStack(modifier.fillMaxWidth(), spacing = Space.sp2) {
            Dot(dot(line.tone), pulses = line.tone == StatusLineModel.Tone.running)
            ChatText(line.text, css(FontSize.fs13), Modifier.weight(1f, fill = false))
            if (line.offersTakeover) {
                // The flex gap and the link's own 4 points of margin.
                Button(onTakeover, Modifier.padding(start = Space.sp1), style = chatInheritedLink) {
                    ChatUnderlinedText(S.chat.takeOver, TextStyle(size = FontSize.fs13))
                }
            }
        }
    }
}

private fun dot(tone: StatusLineModel.Tone): DotStyle = when (tone) {
    StatusLineModel.Tone.running -> DotStyle.Online
    StatusLineModel.Tone.attention -> DotStyle.Attention
    StatusLineModel.Tone.error -> DotStyle.Error
    StatusLineModel.Tone.muted -> DotStyle.Idle
}

private fun ink(tone: StatusLineModel.Tone): Color = when (tone) {
    StatusLineModel.Tone.running -> Palette.running
    StatusLineModel.Tone.attention -> Palette.attention
    StatusLineModel.Tone.error -> Palette.danger
    StatusLineModel.Tone.muted -> Palette.inkSecondary
}
