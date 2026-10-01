package com.junbingao.remotecontrol.win.chat.resume

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.SessionResume
import com.junbingao.remotecontrol.win.chat.support.ChatBorder
import com.junbingao.remotecontrol.win.chat.support.chatBorder
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.btn
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.hex
import com.junbingao.remotecontrol.win.design.overlay.Popover
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.strings.S

/**
 * `web/src/features/chat/ResumeNotice.tsx` — A35: the bar a session with a pending resume carries
 * above its transcript, what happened and when it resumes, with the two actions the ruling allows
 * and no more (`docs/DESIGN.md` § "Paused by the usage limit"). It takes the attention tint the
 * amber dot uses — a pause, not a failure — and the transcript's own width, with a gutter either
 * side.
 */
@Composable
fun ResumeNotice(resume: SessionResume, onSet: suspend (Long) -> Boolean, onCancel: () -> Unit) {
    val stage = LocalPreviewStage.current
    Box(Modifier.fillMaxWidth().padding(top = Space.sp3), contentAlignment = Alignment.TopCenter) {
        WithForeground(Color.hex(0x7A5600)) {
            HStack(
                Modifier
                    .padding(horizontal = Space.sp4)
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .chatBorder(ChatBorder(width = 1.dp, radius = Radius.md), Color.hex(0xECDFBA))
                    .background(Palette.attentionSoft, RoundedCornerShape(Radius.md))
                    .padding(1.dp)
                    .padding(vertical = Space.sp2, horizontal = Space.sp4),
                spacing = Space.sp3,
            ) {
                Text(ResumeWords.noticeText(resume), css(FontSize.fs13), Modifier.weight(1f))
                Popover(
                    align = PopoverAlign.end,
                    chevron = false,
                    ariaLabel = S.chat.resumeChange,
                    initiallyOpen = stage == "chat.resume.change",
                    label = { Text(S.chat.resumeChange) },
                ) { close ->
                    ResumeChangeForm(resume, onSet, onDone = close)
                }
                Button(onCancel, style = btn(ButtonVariant.ghost, ButtonSize.small)) { Text(S.chat.resumeCancel, softWrap = false) }
            }
        }
    }
}
