package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.LocalAppearance
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.scrollIndicator
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActivityIndicator
import com.junbingao.remotecontrol.android.system.IndicatorSize
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.ScrollTail
import kotlinx.coroutines.launch

/**
 * The scrolling transcript. It follows the newest content only while the reader is at the foot of
 * it, and offers a way back down whenever they are not. Paging history keeps the row the reader
 * was looking at in place.
 */
@Composable
internal fun Transcript(chat: ChatStore, composer: ComposerFrame, modifier: Modifier = Modifier) {
    val model = LocalAppModel.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { ScrollTail.threshold.dp.toPx() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val tail = remember(chat, list) { TranscriptTail(chat, list, scope, threshold, composer) }
    // Dragging the transcript lowers the keyboard, as the iPhone's does with the drag.
    SideEffect {
        tail.onDragStart = {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    val rows = chat.rows
    val drawn = remember(rows) { rows.filterNot(TimelineRows::drawsNothing) }
    val reduceMotion = LocalAppearance.current.reduceMotion
    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .scrollIndicator(list)
                .testTag("chat.transcript"),
            state = list,
            contentPadding = PaddingValues(horizontal = Theme.Space.page, vertical = Theme.Space.medium),
            verticalArrangement = Arrangement.spacedBy(Theme.Space.medium),
        ) {
            if (chat.timeline.hasMoreHistory) {
                item(key = TranscriptTail.loadOlderKey) {
                    LoadOlderButton(chat) {
                        val anchor = drawn.firstOrNull()?.id
                        scope.launch {
                            model.perform { chat.loadHistory() }.join()
                            tail.keepInView(anchor)
                        }
                    }
                }
            }
            items(drawn, key = { it.id }) { entry -> TimelineRow(entry, chat) }
            item(key = TranscriptTail.tailKey) { Spacer(Modifier.fillMaxWidth().height(1.dp)) }
        }
        AnimatedVisibility(
            visible = !chat.isFollowingTail,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Theme.Space.small),
            enter = fadeIn(tween(if (reduceMotion) 0 else 180, easing = EaseInOut)) +
                scaleIn(tween(if (reduceMotion) 0 else 180, easing = EaseInOut), initialScale = 0.92f),
            exit = fadeOut(tween(if (reduceMotion) 0 else 180, easing = EaseInOut)) +
                scaleOut(tween(if (reduceMotion) 0 else 180, easing = EaseInOut), targetScale = 0.92f),
        ) {
            JumpToLatestButton(chat) { tail.scrollToTail() }
        }
    }
    LaunchedEffect(tail) { tail.watch() }
    LaunchedEffect(tail, chat.timeline.lastSeq) { tail.followNewContent() }
    // A12: a message this app has just sent carries no `seq`, so the cursor above cannot see it
    // arrive. Without this the bubble would be added below the fold on a full screen.
    LaunchedEffect(tail, chat.timeline.optimistic.size) { tail.followNewContent() }
    DisposableEffect(tail) { onDispose { tail.endJump() } }
}

@Composable
private fun LoadOlderButton(chat: ChatStore, load: () -> Unit) {
    Button(
        onClick = load,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Theme.Touch.minimum)
            .testTag("chat.loadOlder"),
    ) {
        if (chat.isLoadingHistory) {
            ActivityIndicator(size = IndicatorSize.mini)
        } else {
            Text(L10n.string("Load earlier messages"), style = SystemFont.footnote, color = Theme.inkSecondary)
        }
    }
}
