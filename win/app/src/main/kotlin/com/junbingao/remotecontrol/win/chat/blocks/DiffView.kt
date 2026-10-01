package com.junbingao.remotecontrol.win.chat.blocks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.DiffPayload
import com.junbingao.remotecontrol.win.chat.support.ChatBoundedScroll
import com.junbingao.remotecontrol.win.chat.support.ChatEqualWidthColumn
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
 * `web/src/features/chat/blocks/DiffView.tsx`: a unified patch, one line per row, additions and
 * deletions on their tints, hunk and file headers quiet.
 */
@Composable
fun DiffView(diff: DiffPayload) {
    val patch = diff.patch
    if (patch.isNullOrEmpty()) return
    val lines = patch.split("\n")
    val kinds = lines.map { DiffLineKind(it) }
    VStack(Modifier.fillMaxWidth().chatBox(radius = Radius.sm), spacing = 0.dp, alignment = Alignment.Start) {
        ChatBoundedScroll(maxHeight = 420.dp, modifier = Modifier.fillMaxWidth()) {
            // The pane offers its own width as the least, so every line's tint runs edge to edge.
            ChatEqualWidthColumn(tints = kinds.map(::tint), modifier = Modifier.padding(vertical = Space.sp2)) {
                for ((index, line) in lines.withIndex()) DiffLine(line, kinds[index])
            }
        }
        if (diff.patchTruncated) {
            Text(
                S.chat.patchTruncated,
                css(FontSize.fs12),
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(Palette.line, size = Size(size.width, 1.dp.toPx())) }
                    .padding(top = 1.dp)
                    .padding(top = 4.dp, bottom = 6.dp, start = Space.sp3, end = Space.sp3),
                color = Palette.inkSecondary,
            )
        }
    }
}

/** `.diff-line`: a block the width of the pane, so its tint — painted by the column — runs edge to edge. */
@Composable
private fun DiffLine(line: String, kind: DiffLineKind) {
    ChatText(
        line.ifEmpty { " " },
        css(FontSize.fs12, lineHeight = 1.6f, mono = true),
        Modifier.padding(horizontal = Space.sp3),
        color = ink(kind),
        softWrap = false,
    )
}

private fun ink(kind: DiffLineKind): Color = when (kind) {
    DiffLineKind.add -> Palette.diffAdd
    DiffLineKind.del -> Palette.diffDel
    DiffLineKind.hunk, DiffLineKind.meta -> Palette.inkTertiary
    DiffLineKind.context -> Palette.ink
}

private fun tint(kind: DiffLineKind): Color? = when (kind) {
    DiffLineKind.add -> Palette.diffAddBg
    DiffLineKind.del -> Palette.diffDelBg
    else -> null
}

/** `DiffStat`: "+12 −4" in the diff colours, monospace. */
@Composable
fun DiffStat(diff: DiffPayload) {
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = Palette.diffAdd)) { append("+${diff.additions}") }
        append(" ")
        withStyle(SpanStyle(color = Palette.diffDel)) { append("−${diff.deletions}") }
    }
    Text(text, css(FontSize.fs12, mono = true), softWrap = false)
}
