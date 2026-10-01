package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.scrollIndicator
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.navigation.NavigationMetrics
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActionRole
import com.junbingao.remotecontrol.android.system.BarTextButton
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.android.system.SwipeAction
import com.junbingao.remotecontrol.android.system.SwipeActions
import com.junbingao.remotecontrol.android.system.contextMenu
import com.junbingao.remotecontrol.android.system.destructiveTint
import com.junbingao.remotecontrol.core.protocol.QueuedMessage
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.RelativeTime

/**
 * Messages waiting behind the current turn, in the order they will go.
 *
 * `docs/DESIGN.md` § "The composer" → **Up next** (A43): a row is taken out of the line for good
 * with a swipe, or with Remove in its context menu, and nothing asks first. Tapping it takes the
 * message back into the composer to be edited: the list closes, the device lets go of the message,
 * and the words come into the field. A message that carries files is removed and never edited,
 * since its files are on the device and nothing brings them back; and while the composer cannot
 * send, or already holds a message being edited, every row offers Remove and nothing else.
 */
@Composable
internal fun QueueSheet(chat: ChatStore, dismiss: () -> Unit) {
    val model = LocalAppModel.current
    val list = rememberLazyListState()
    // The list closes at once and the request to let go of the message goes with it; the field
    // takes the words once the device has. They are out of the line from then on, so the draft
    // that now holds them is saved at once.
    val edit = { message: QueuedMessage ->
        dismiss()
        model.perform {
            chat.beginEdit(message)
            saveDraft()
        }
    }
    NavigationScreen(
        L10n.string("Up next"),
        displayMode = TitleDisplayMode.inline,
        showsBack = false,
        listState = list,
        trailing = { BarTextButton(L10n.string("Done"), onClick = dismiss, prominent = true) },
    ) { insets ->
        val line = SystemColor.separator
        LazyColumn(
            Modifier.fillMaxSize().scrollIndicator(list),
            state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = insets.top + NavigationMetrics.barFoot, bottom = insets.bottom),
        ) {
            items(chat.timeline.queue, key = { it.id }) { message ->
                QueueRow(
                    message,
                    canEdit = chat.canEdit(message),
                    first = message.id == chat.timeline.queue.firstOrNull()?.id,
                    separator = line,
                    edit = { edit(message) },
                    remove = { chat.removeQueued(message.id) },
                )
            }
            if (chat.timeline.queue.isEmpty()) {
                item(key = "empty") {
                    Text(
                        L10n.string("Nothing is queued."),
                        Modifier.padding(horizontal = PlainListMetrics.inset, vertical = 11.dp),
                        style = SystemFont.footnote,
                        color = Theme.inkSecondary,
                    )
                }
            }
        }
    }
}

/**
 * One queued message: two lines of its words at most, how long it has waited, and, for a message
 * that carries files, a paperclip and how many. A row that can be edited is a button, and one that
 * cannot is only read, so a tap on it does nothing at all.
 */
@Composable
private fun QueueRow(
    message: QueuedMessage,
    canEdit: Boolean,
    first: Boolean,
    separator: androidx.compose.ui.graphics.Color,
    edit: () -> Unit,
    remove: () -> Unit,
) {
    val danger = destructiveTint
    val menu = buildList {
        if (canEdit) add(MenuItem.Action(L10n.string("Edit"), symbol = Sf.pencil, action = edit))
        add(MenuItem.Action(L10n.string("Remove"), symbol = Sf.trash, role = ActionRole.destructive, action = remove))
    }
    SwipeActions(listOf(SwipeAction(L10n.string("Remove"), Sf.trash, danger, action = remove)), Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Theme.surface)
                .drawBehind {
                    // The plain list's hairlines: between rows, under the last one, and over the
                    // first, inset to the text on both sides.
                    val inset = PlainListMetrics.inset.toPx()
                    val y = size.height - 0.25.dp.toPx()
                    drawLine(separator, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 0.5.dp.toPx())
                    if (first) drawLine(separator, Offset(inset, 0.25.dp.toPx()), Offset(size.width - inset, 0.25.dp.toPx()), strokeWidth = 0.5.dp.toPx())
                }
                .contextMenu(menu, onClick = if (canEdit) edit else null)
                .semantics(mergeDescendants = true) {}
                .testTag("queue.entry"),
        ) {
            Content(message)
        }
    }
}

@Composable
private fun Content(message: QueuedMessage) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PlainListMetrics.inset, vertical = PlainListMetrics.vertical),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(message.text, Modifier.fillMaxWidth(), style = SystemFont.subheadline, color = Theme.ink, lineLimit = 2)
        Foreground(Theme.inkSecondary, SystemFont.caption) {
            Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
                Text(RelativeTime.short(since = message.ts))
                val files = message.attachments
                if (message.carriesFiles && files != null) {
                    Row(
                        Modifier.semantics(mergeDescendants = true) {
                            contentDescription = L10n.string(if (files == 1) "%lld file" else "%lld files", files)
                        },
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Sf.paperclip)
                        Text("$files")
                    }
                }
            }
        }
    }
}

/** A plain list's row, from the iPhone 17 reference screenshots (`99-queue-list`). */
internal object PlainListMetrics {
    /** The text and the hairlines stand this far in from the screen's edges. */
    val inset = 16.dp

    /** A two-line row stands 66 points from hairline to hairline. */
    val vertical = 15.dp
}
