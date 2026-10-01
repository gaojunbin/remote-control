package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.awake.keepsScreenAwake
import com.junbingao.remotecontrol.android.design.EmptyStateView
import com.junbingao.remotecontrol.android.design.NoticeBanner
import com.junbingao.remotecontrol.android.design.OneAtATime
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.dismissesKeyboardOnBackgroundTap
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.navigation.HidesTabBar
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.screens.chat.voice.LocalVoiceGlow
import com.junbingao.remotecontrol.android.screens.chat.voice.VoiceGlowLayer
import com.junbingao.remotecontrol.android.screens.chat.voice.VoiceGlowState
import com.junbingao.remotecontrol.android.shell.AppModel
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActivityIndicator
import com.junbingao.remotecontrol.android.system.BarIconButton
import com.junbingao.remotecontrol.android.system.Sheet
import com.junbingao.remotecontrol.android.system.safeArea
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.RelativeTime
import com.junbingao.remotecontrol.core.state.canStop
import kotlinx.coroutines.delay

/**
 * One conversation: the transcript, the status line and the composer.
 *
 * The navigation stack carries the session key; the session itself is resolved from the live list
 * on every pass. A tap anywhere off the message field puts the keyboard away, because reading is
 * the usual reason to touch this screen, and the conversation is the unit the screen stays awake
 * for — the composer, dictation included, is inside it, so one rule covers typing and talking.
 */
@Composable
fun ChatView(sessionKey: String) {
    val model = LocalAppModel.current
    var showsQueue by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf("") }
    val glow = remember { VoiceGlowState() }
    val session = model.session(sessionKey)
    val chat = model.chat?.takeIf { it.key == sessionKey }
    val title = (chat?.session?.title ?: session?.title ?: "").ifEmpty { L10n.string("Session") }
    HidesTabBar()
    LaunchedEffect(sessionKey) {
        val opening = model.session(sessionKey) ?: return@LaunchedEffect
        if (model.chat?.key != sessionKey) model.perform { open(opening) }
    }
    // The key, not "whatever is open": a conversation a notification replaced is told to close
    // after its replacement is already installed (`docs/DESIGN.md` § "Status vocabulary" → **A
    // notification opens its session in place**).
    DisposableEffect(sessionKey) { onDispose { model.perform { closeChat(sessionKey) } } }
    LaunchedEffect(Unit) {
        while (true) {
            elapsed = elapsedText(model)
            delay(1_000)
        }
    }
    // The transcript sits below the subtitle bar, not under the navigation bar, so the bar's scroll
    // edge never shows: the screen is told of a list that never moves.
    val still = remember { LazyListState() }
    CompositionLocalProvider(LocalVoiceGlow provides glow) {
        Box(
            Modifier
                .fillMaxSize()
                .dismissesKeyboardOnBackgroundTap()
                .keepsScreenAwake()
                .testTag("chat.$sessionKey"),
        ) {
            NavigationScreen(
                title,
                displayMode = TitleDisplayMode.inline,
                listState = still,
                trailing = {
                    if (chat != null && chat.canStop) {
                        BarIconButton(Sf.stopCircle, L10n.string("Stop"), onClick = { model.perform { chat.stop() } }, tint = Theme.ink, tag = "chat.stop")
                    }
                },
            ) { insets ->
                val safe = safeArea()
                val top = insets.top + ChatMetrics.inlineBarGap
                when {
                    chat != null -> Conversation(chat, model, elapsed, top, bottom = maxOf(insets.bottom, safe.keyboard)) { showsQueue = true }
                    session == null -> Box(Modifier.fillMaxSize().padding(top = top), contentAlignment = Alignment.Center) {
                        EmptyStateView(
                            "bubble.left.and.exclamationmark.bubble.right",
                            L10n.string("This session is gone"),
                            L10n.string("The gateway no longer lists it. It may have been deleted on the device."),
                        )
                    }
                    else -> Box(Modifier.fillMaxSize().padding(top = top), contentAlignment = Alignment.Center) { ActivityIndicator() }
                }
            }
            VoiceGlowLayer(glow)
        }
    }
    if (chat != null) {
        Sheet(isPresented = showsQueue, onDismiss = { showsQueue = false }) { QueueSheet(chat) { showsQueue = false } }
    }
}

@Composable
private fun Conversation(chat: ChatStore, model: AppModel, elapsed: String, top: Dp, bottom: Dp, showsQueue: () -> Unit) {
    // Retry reuses the original request id, so two overlapping retries would put two requests
    // under one id. One at a time.
    val retry = remember { OneAtATime() }
    val composer = remember(chat) { ComposerFrame() }
    Column(Modifier.fillMaxSize().padding(top = top)) {
        SubtitleBar(chat.session, model.device(chat.session), elapsed, chat.timeline.todos, drawsTodos = chat.showsTodos)
        chat.unconfirmedSend?.let { pending ->
            NoticeBanner(
                text = L10n.string("Delivery unconfirmed. Nothing was resent automatically."),
                tint = Theme.attention,
                actionTitle = L10n.string("Retry"),
                action = { model.perform { retry.run { chat.retry(pending) } } },
                actionEnabled = !retry.isBusy,
                dismiss = { chat.dismiss(pending) },
            )
        }
        // Amendment A35: a session paused by the usage limit says so where the session is, with a
        // way to move the time and a way to end it. The row goes when `resume` does.
        chat.resume?.let { ResumeNotice(chat, it) }
        chat.errorMessage?.let { error -> NoticeBanner(text = L10n.platform(error), dismiss = { chat.clearError() }) }
        // The transcript is the view that gives way. Everything in the composer is fixed except
        // the command card (A27), which grows to its cap and no further.
        Transcript(chat, composer, Modifier.weight(1f))
        StatusLine(chat)
        Composer(
            chat,
            showsQueue = showsQueue,
            bottomInset = bottom,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { composer.height = it.height },
        )
    }
}

private fun elapsedText(model: AppModel): String {
    val seconds = model.chat?.elapsedSinceTurnStart ?: return ""
    return RelativeTime.duration(milliseconds = seconds.inWholeMilliseconds.toInt())
}

/** Shared measurements of the conversation's screens. */
internal object ChatMetrics {
    /**
     * iOS 26's navigation bar stands 54 points tall under an inline title where its row is 44:
     * what is laid out under it starts ten points below the buttons.
     */
    val inlineBarGap = 10.dp
}
