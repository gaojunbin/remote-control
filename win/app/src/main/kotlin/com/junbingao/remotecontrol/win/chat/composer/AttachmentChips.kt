package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.IconBtn
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.attachment-list`: the files the next message carries, one capsule each — the paperclip, the
 * name in the monospaced face, the size at the trailing edge, and the × that takes the file off.
 */
@Composable
fun AttachmentChips(attachments: List<ComposerAttachment>, modifier: Modifier = Modifier, onRemove: (Int) -> Unit) {
    VStack(modifier.fillMaxWidth(), spacing = Space.sp1) {
        for ((index, file) in attachments.withIndex()) {
            key(file.id) { AttachmentChip(file) { onRemove(index) } }
        }
    }
}

@Composable
private fun AttachmentChip(file: ComposerAttachment, onRemove: () -> Unit) {
    val capsule = RoundedCornerShape(percent = 50)
    HStack(
        Modifier
            .fillMaxWidth()
            .background(Palette.surfaceSunken, capsule)
            .border(1.dp, Palette.line, capsule)
            .padding(start = Space.sp3 + 1.dp, top = 6.dp, end = Space.sp2 + 1.dp, bottom = 6.dp),
        spacing = Space.sp2,
    ) {
        Icon(LucideIcon.paperclip, size = 12.dp)
        Text(file.name, css(FontSize.fs12, mono = true), Modifier.weight(1f), lineLimit = 1)
        Text(Format.bytes(file.size), css(FontSize.fs11), color = Palette.inkSecondary, softWrap = false)
        IconBtn(LucideIcon.x, size = 13.dp, label = S.common.remove, action = onRemove)
    }
}
