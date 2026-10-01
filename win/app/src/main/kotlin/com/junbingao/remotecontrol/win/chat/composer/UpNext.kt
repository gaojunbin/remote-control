package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.QueuedMessage
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Help
import com.junbingao.remotecontrol.win.design.IconBtn
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.ThinScrollView
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.overlay.Popover
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.design.overlay.PopoverSide
import com.junbingao.remotecontrol.win.strings.S

/**
 * A43 — the queue as one control (`web/src/features/chat/UpNext.tsx`). The chip that leads the
 * control row says how many messages wait behind the turn, and only while one does; the list it
 * opens has them in the order they will go, one line each. × takes a message out of the line for
 * good, and a click on one edits it. A message that carries files has only the ×: its files are on
 * the device, and nothing can bring them back into the field.
 */
@Composable
fun UpNext(composer: ComposerModel, initiallyOpen: Boolean) {
    val queue = composer.chat.timeline.queue
    // The last one removed takes the chip with it, and the open list goes too.
    if (queue.isEmpty()) return
    Popover(
        align = PopoverAlign.start,
        side = PopoverSide.top,
        chevron = false,
        triggerStyle = ComposerChipStyle,
        initiallyOpen = initiallyOpen,
        label = { Text(S.composer.upNextCount(queue.size), css(FontSize.fs12), softWrap = false) },
    ) { close ->
        UpNextList(composer, close)
    }
}

/** `.up-next`: the title and the rows, at least 240 px wide. */
@Composable
private fun UpNextList(composer: ComposerModel, close: () -> Unit) {
    VStack(Modifier.widthIn(min = 240.dp), spacing = 2.dp, alignment = Alignment.Start) {
        Text(
            S.composer.upNext,
            css(FontSize.fs12, weight = FontWeight.Medium),
            Modifier.padding(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 2.dp),
            color = Palette.inkSecondary,
        )
        CappedScroll(maximum = 320.dp) {
            VStack(Modifier.fillMaxWidth(), spacing = 0.dp) {
                for (entry in composer.chat.timeline.queue) {
                    key(entry.id) {
                        UpNextRow(
                            entry,
                            editable = composer.canEdit(entry),
                            onEdit = {
                                // The list closes as the words go into the field.
                                close()
                                composer.edit(entry)
                            },
                            onRemove = { composer.remove(entry) },
                        )
                    }
                }
            }
        }
    }
}

/** One waiting message: a button that edits it, or plain text where it cannot, and its ×. */
@Composable
private fun UpNextRow(entry: QueuedMessage, editable: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    HStack(Modifier.fillMaxWidth(), spacing = 2.dp) {
        Help(entry.text, Modifier.weight(1f)) {
            Words(
                entry,
                Modifier
                    .fillMaxWidth()
                    .background(if (editable && hovered) Palette.surfaceHover else Color.Transparent, RoundedCornerShape(Radius.sm))
                    .hoverable(source)
                    .then(
                        if (editable) {
                            Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onEdit)
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 7.dp, horizontal = 10.dp),
            )
        }
        IconBtn(LucideIcon.x, size = 14.dp, label = S.chat.queuedRemove, action = onRemove)
    }
}

/** The message on one line — `white-space: nowrap` runs its lines into one — and its files. */
@Composable
private fun Words(entry: QueuedMessage, modifier: Modifier) {
    val files = entry.attachments ?: 0
    HStack(modifier, spacing = Space.sp2) {
        Text(oneLine(entry.text), css(FontSize.fs14), Modifier.weight(1f), lineLimit = 1)
        if (files > 0) {
            WithForeground(Palette.inkSecondary) {
                HStack(Modifier.clearAndSetSemantics { contentDescription = S.chat.attachments(files) }, spacing = 3.dp) {
                    Icon(LucideIcon.paperclip, size = 12.dp)
                    Text("$files", css(FontSize.fs12), softWrap = false)
                }
            }
        }
    }
}

private val whitespace = Regex("(?U)\\s+")

private fun oneLine(text: String): String = text.split(whitespace).filter { it.isNotEmpty() }.joinToString(" ")

/**
 * A43: what sits over the field while it holds a queued message. Cancel puts the words back as they
 * were queued, in the place they left; while words are on their way back there is nothing left to
 * cancel.
 */
@Composable
fun EditingStrip(canCancel: Boolean, modifier: Modifier = Modifier, onCancel: () -> Unit) {
    HStack(
        modifier
            .fillMaxWidth()
            .background(Palette.surfaceMuted, RoundedCornerShape(Radius.md))
            .padding(vertical = 6.dp, horizontal = Space.sp3),
        spacing = Space.sp3,
    ) {
        WithForeground(Palette.inkSecondary) {
            HStack(Modifier.weight(1f), spacing = 6.dp) {
                Icon(LucideIcon.pencil, size = 13.dp)
                Text(S.composer.editingQueued, css(FontSize.fs13), Modifier.weight(1f))
            }
        }
        Disabled(!canCancel) { LinkButton(S.common.cancel, FontSize.fs13, action = onCancel) }
    }
}

/** A list no taller than `maximum`, which scrolls past it: `max-height` with `overflow-y: auto`, in a panel that is otherwise as tall as what it holds. */
@Composable
fun CappedScroll(maximum: Dp, content: @Composable () -> Unit) {
    Box(Modifier.heightIn(max = maximum)) { ThinScrollView(content = content) }
}
