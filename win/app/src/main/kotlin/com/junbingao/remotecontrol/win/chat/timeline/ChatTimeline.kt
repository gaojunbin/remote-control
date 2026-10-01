package com.junbingao.remotecontrol.win.chat.timeline

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.ThinScrollbar
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `web/src/features/chat/Timeline.tsx`: the transcript, 760 points at most and centred, its rows 16
 * apart, the status line as its last row, and the way back down whenever the reader is away from
 * the tail (`docs/DESIGN.md` § "Reading position"). Rows are laid out lazily, so a long day of
 * work costs what is on screen.
 */
@Composable
fun ChatTimeline(chat: ChatStore, statusLine: StatusLineModel?, onTakeover: () -> Unit, handlers: TranscriptHandlers) {
    val model = LocalAppModel.current
    val layout = LocalLayoutClass.current
    val stage = LocalPreviewStage.current
    val density = LocalDensity.current.density
    val scroll = remember { TranscriptScroll() }
    val selections = remember { TranscriptSelectionCache() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val detail = model.settings.timelineDetail
    val selection = selections.selection(chat.timeline, detail)
    val rows = selection.roots.filter(TranscriptRow::draws)
    val notes = TranscriptNotes.of(loading = chat.isLoadingHistory, hasMore = chat.timeline.hasMoreHistory, rowCount = selection.rowCount)
    val compact = layout.maxWidth1023
    val keys by rememberUpdatedState(notes.map { it.key } + rows.map { it.id })
    val mover = remember { TranscriptMover(list, scope) }
    val exact = remember { TranscriptExact() }
    val apply: (List<TranscriptScroll.Action>) -> Unit = { actions ->
        for (action in actions) {
            when (action) {
                is TranscriptScroll.Action.ScrollToBottom -> mover.toBottom(action.animated, onTail = scroll::reachedTail)
                is TranscriptScroll.Action.Restore -> keys.indexOf(action.key).takeIf { it >= 0 }?.let { mover.restore(it, action.offset) }
                TranscriptScroll.Action.LoadOlder ->
                    if (chat.timeline.hasMoreHistory && !chat.isLoadingHistory) model.tasks.launch { chat.loadHistory() }
            }
        }
    }
    val rowsKey = ScrollFollow.Rows(
        // A pending send carries no `seq`, so it is counted into the revision as well.
        revision = "${selection.newestSeq}.${chat.timeline.optimistic.size}",
        redraw = detail,
        firstKey = selection.firstKey,
        lastKey = selection.lastKey,
    )
    LaunchedEffect(rowsKey) { apply(scroll.rowsChanged(rowsKey)) }
    LaunchedEffect(list) {
        snapshotFlow { TranscriptGeometry.of(list.layoutInfo, density) { key -> key !is TranscriptNotes.Key && key != STATUS } }
            .collect { apply(scroll.geometryChanged(it)) }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress && !mover.isMoving }.collect { scroll.phaseChanged(it) }
    }
    // A render shows the way back down from the top of the transcript, where a reader who
    // scrolled up would see it — and the open tool rows from there, where the first of them are.
    LaunchedEffect(Unit) {
        if (stage != "chat.jump" && stage != "chat.tools.open") return@LaunchedEffect
        delay(1_200)
        list.scrollToItem(0)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        LazyColumn(
            Modifier.fillMaxSize().then(exact.modifier { list.layoutInfo }),
            state = list,
            // The tail takes the last point of the bottom padding, as the Mac's tail marker does.
            contentPadding = PaddingValues(top = if (compact) Space.sp4 else Space.sp6, bottom = if (compact) Space.sp3 else Space.sp4),
            verticalArrangement = Arrangement.spacedBy(Space.sp4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (note in notes) {
                item(key = note.key) { TranscriptColumn(exact, note.key, compact) { TranscriptNote(note.text) } }
            }
            itemsIndexed(rows, key = { _, item -> item.id }) { index, item ->
                TranscriptColumn(exact, item.id, compact) {
                    TranscriptRow(
                        item,
                        children = selection.children[item.id] ?: emptyList(),
                        followsTool = index > 0 && TranscriptRow.isTool(rows[index - 1]),
                        chat = chat,
                        handlers = handlers,
                    )
                }
            }
            if (statusLine != null) {
                item(key = STATUS) { TranscriptColumn(exact, STATUS, compact) { StatusLineView(statusLine, onTakeover) } }
            }
        }
        ThinScrollbar(rememberScrollbarAdapter(list), Orientation.Vertical, Modifier.matchParentSize())
        if (!scroll.following) {
            BackToLatest(scroll.missed, Modifier.padding(bottom = Space.sp4)) { apply(scroll.jump()) }
        }
    }
}

private const val STATUS = "chat.status"

/** The transcript's column: 760 points at most, centred, with the gutter either side, each row where the Mac's would be. */
@Composable
private fun TranscriptColumn(exact: TranscriptExact, key: Any, compact: Boolean, content: @Composable () -> Unit) {
    exact.Item(key, Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = if (compact) Space.sp4 else Space.sp5), content)
}

/** `.timeline-note` and `.timeline-empty`: where the conversation starts, or that there is nothing in it yet, in the tertiary ink. */
private object TranscriptNotes {
    data class Key(val name: String)

    data class Note(val key: Key, val text: String)

    fun of(loading: Boolean, hasMore: Boolean, rowCount: Int): List<Note> {
        val notes = mutableListOf<Note>()
        if (loading) {
            notes += Note(Key("loading"), S.chat.loadingHistory)
        } else if (!hasMore && rowCount > 0) {
            notes += Note(Key("start"), S.chat.historyStart)
        }
        if (rowCount == 0 && !loading) notes += Note(Key("empty"), S.chat.emptyTimeline)
        return notes
    }
}

@Composable
private fun TranscriptNote(text: String) {
    Text(text, css(FontSize.fs12), Modifier.fillMaxWidth(), color = Palette.inkTertiary, textAlign = TextAlign.Center)
}

/**
 * The list's own scrolls, which the follow rule makes: to the tail — however far away, and
 * however the list guessed the heights of rows it has not laid out — and back to a row a
 * prepended page moved. While one runs the reader is not scrolling.
 */
private class TranscriptMover(private val list: LazyListState, private val scope: CoroutineScope) {
    var isMoving = false
        private set

    fun toBottom(animated: Boolean, onTail: () -> Unit) {
        scope.launch {
            move {
                val last = list.layoutInfo.totalItemsCount - 1
                if (last < 0) return@move
                if (animated) list.animateScrollToItem(last) else list.scrollToItem(last)
                list.scrollBy(TO_THE_END)
            }
            if (!list.canScrollForward) onTail()
        }
    }

    fun restore(index: Int, offset: Int) {
        scope.launch { move { list.scrollToItem(index, -offset) } }
    }

    private suspend fun move(block: suspend () -> Unit) {
        isMoving = true
        try {
            block()
        } finally {
            isMoving = false
        }
    }

    private companion object {
        /** More than any last row is tall: the list stops at its end. */
        const val TO_THE_END = 1_000_000f
    }
}
