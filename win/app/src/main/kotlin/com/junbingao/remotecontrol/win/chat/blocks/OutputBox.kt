package com.junbingao.remotecontrol.win.chat.blocks

import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.chat.support.ChatBoundedScroll
import com.junbingao.remotecontrol.win.chat.support.ChatText
import com.junbingao.remotecontrol.win.chat.support.ChatUnderlinedText
import com.junbingao.remotecontrol.win.chat.support.chatBox
import com.junbingao.remotecontrol.win.chat.support.chatLink
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.TextStyle
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch

/**
 * `web/src/features/chat/blocks/OutputBox.tsx`: a monospace output pane, folded beyond 20 lines,
 * with "Show more" and — where the device withheld part of it — "Open full output". A live pane
 * shows everything and folds nothing, because the tool is still writing to it.
 */
@Composable
fun OutputBox(text: String, truncated: Boolean = false, live: Boolean = false, onOpenFull: (suspend () -> Unit)? = null) {
    val model = LocalAppModel.current
    var expanded by remember { mutableStateOf(false) }
    var loadingFull by remember { mutableStateOf(false) }
    val fold = Format.foldLines(text, maxLines = FOLD_LINES)
    val shown = if (expanded || live) text else fold.head
    VStack(Modifier.fillMaxWidth().chatBox(radius = Radius.sm, background = Palette.surface), spacing = 0.dp, alignment = Alignment.Start) {
        ChatBoundedScroll(maxHeight = 380.dp, modifier = Modifier.fillMaxWidth()) {
            ChatText(
                ChatPreText.display(shown),
                css(FontSize.fs12, lineHeight = 1.65f, mono = true),
                Modifier.padding(vertical = 10.dp, horizontal = Space.sp3),
                color = Palette.ink,
                softWrap = false,
            )
        }
        if ((fold.folded && !live) || truncated) {
            OutputActions(
                fold = fold,
                live = live,
                truncated = truncated,
                expanded = expanded,
                loadingFull = loadingFull,
                canOpenFull = onOpenFull != null,
                onToggle = { expanded = !expanded },
                onOpenFull = {
                    val open = onOpenFull
                    if (open != null) {
                        loadingFull = true
                        model.tasks.launch {
                            open()
                            loadingFull = false
                        }
                    }
                },
            )
        }
    }
}

private const val FOLD_LINES = 20

/** The pane's foot: Show more or less, and Open full output, on the sunken surface under a rule. */
@Composable
private fun OutputActions(
    fold: Format.Folded,
    live: Boolean,
    truncated: Boolean,
    expanded: Boolean,
    loadingFull: Boolean,
    canOpenFull: Boolean,
    onToggle: () -> Unit,
    onOpenFull: () -> Unit,
) {
    HStack(
        Modifier
            .fillMaxWidth()
            // `border-top`, which takes a point of its own above the padding.
            .drawBehind { drawRect(Palette.line, size = Size(size.width, 1.dp.toPx())) }
            .padding(top = 1.dp)
            .background(Palette.surfaceSunken)
            .padding(vertical = 6.dp, horizontal = Space.sp3),
        spacing = Space.sp3,
    ) {
        if (fold.folded && !live) {
            Button(onToggle, style = chatLink) {
                ChatLinkLabel(if (expanded) S.common.showLess else "${S.common.showMore} (${fold.total} lines)")
            }
        }
        if (truncated) {
            Disabled(loadingFull || !canOpenFull) {
                Button(onOpenFull, style = chatLink) {
                    ChatLinkLabel(if (loadingFull) S.common.loading else S.chat.openFullOutput)
                }
            }
        }
    }
}

/** The words of a `.link-btn` at the 12-point size of the row it sits in. */
@Composable
fun ChatLinkLabel(text: String, size: Float = FontSize.fs12) {
    ChatUnderlinedText(text, TextStyle(size = size))
}

/**
 * Text laid out as `white-space: pre` or `pre-wrap` lays it out. A line feed that ends the text
 * ends its last line and starts no new one.
 */
object ChatPreText {
    fun display(text: String): String = if (text.endsWith("\n")) text.dropLast(1) else text
}
