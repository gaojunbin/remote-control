package com.junbingao.remotecontrol.win.chat.markdown

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.chat.support.ChatBorder
import com.junbingao.remotecontrol.win.chat.support.ChatBoundedScroll
import com.junbingao.remotecontrol.win.chat.support.ChatText
import com.junbingao.remotecontrol.win.chat.support.chatBare
import com.junbingao.remotecontrol.win.chat.support.chatBorder
import com.junbingao.remotecontrol.win.chat.support.chatBox
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.hex
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.strings.S
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * `CodeBlock` in `Markdown.tsx` with `chat.css`'s `.md-pre` and the github theme: a bordered block
 * on the sunken surface, 12 points of padding, 12-point monospace on a 1.6 line, scrolling
 * sideways rather than wrapping. A block rehype-highlight marked `hljs` holds a white inner box of
 * its own, 12 points in again (`pre code.hljs`), in the theme's ink. The copy control shows while
 * the pointer is over the block.
 */
@Composable
fun MDCodeBlock(code: MDCode, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shown by animateFloatAsState(if (hovered) 1f else 0f, Motion.ease(Motion.durFast))
    Box(modifier.fillMaxWidth().hoverable(source), contentAlignment = Alignment.TopEnd) {
        Pre(code)
        MDCopyButton(code.source, Modifier.padding(6.dp).alpha(shown))
    }
}

@Composable
private fun Pre(code: MDCode) {
    if (code.highlighted) {
        ChatBoundedScroll(
            maxHeight = Dp.Infinity,
            modifier = Modifier
                .fillMaxWidth()
                .chatBox(radius = Radius.sm, background = Palette.surfaceSunken)
                .padding(Space.sp3)
                .background(Color.White),
        ) { Lines(code) }
    } else {
        ChatBoundedScroll(
            maxHeight = Dp.Infinity,
            modifier = Modifier.fillMaxWidth().chatBox(radius = Radius.sm, background = Palette.surfaceSunken),
        ) { Lines(code) }
    }
}

@Composable
private fun Lines(code: MDCode) {
    val text = remember(code) { text(code) }
    ChatText(text, css(FontSize.fs12, lineHeight = 1.6f, mono = true), Modifier.padding(Space.sp3), softWrap = false)
}

/** The block's lines as one text, every run in the theme's ink, weight, slant and tint. */
private fun text(code: MDCode): AnnotatedString {
    val builder = AnnotatedString.Builder()
    for ((index, line) in code.lines.withIndex()) {
        if (index > 0) builder.append("\n")
        for (run in line) {
            val start = builder.length
            builder.append(run.text)
            val style = run.style
            builder.addStyle(
                SpanStyle(
                    color = Color.hex(style.color),
                    fontWeight = if (style.bold) FontWeight.Bold else null,
                    fontStyle = if (style.italic) FontStyle.Italic else null,
                    background = style.background?.let { Color.hex(it) } ?: Color.Unspecified,
                ),
                start,
                builder.length,
            )
        }
    }
    return builder.toAnnotatedString()
}

/**
 * `.md-copy`: a 26-point square on the surface with a light edge, the copy icon in the secondary
 * ink, a check for a moment once the code is copied.
 */
@Composable
private fun MDCopyButton(source: String, modifier: Modifier) {
    val model = LocalAppModel.current
    var copied by remember { mutableStateOf(false) }
    Button(
        {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(source), null)
            copied = true
            model.tasks.launch {
                delay(1_400)
                copied = false
            }
        },
        modifier,
        style = chatBare,
        accessibilityLabel = S.chat.copyCode,
    ) {
        Box(
            Modifier
                .chatBorder(ChatBorder(width = 1.dp, radius = 6.dp), Palette.line)
                .background(Palette.surface, RoundedCornerShape(6.dp))
                .padding(1.dp)
                .size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (copied) LucideIcon.check else LucideIcon.copy, size = 13.dp, color = Palette.inkSecondary)
        }
    }
}
