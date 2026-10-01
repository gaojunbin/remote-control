package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.junbingao.remotecontrol.android.awake.keepsScreenAwake
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.navigation.HidesTabBar
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.core.protocol.SessionEventBody

/**
 * Placeholder for `android-chat`, which ports the iPhone's `ChatView` (with its rows, cards,
 * composer, voice and Markdown) here and in subpackages of this one. What it does now is what the
 * shell relies on: the conversation hides the tab bar, holds the screen awake, reads the open
 * store the model installed for [sessionKey], and tells the model when it leaves —
 * `closeChat(key)`, the named close a replacing conversation cannot trip over.
 */
@Composable
fun ChatView(sessionKey: String) {
    val model = LocalAppModel.current
    val session = model.session(sessionKey)
    val chat = model.chat?.takeIf { it.key == sessionKey }
    val list = rememberLazyListState()
    HidesTabBar()
    DisposableEffect(sessionKey) { onDispose { model.perform { closeChat(sessionKey) } } }
    Box(Modifier.fillMaxSize().keepsScreenAwake().testTag("chat.$sessionKey")) {
        NavigationScreen(session?.title.orEmpty(), displayMode = TitleDisplayMode.inline, listState = list) { insets ->
            LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(top = insets.top, bottom = insets.bottom)) {
                items(chat?.timeline?.entries.orEmpty(), key = { it.id }) { entry ->
                    val words = when (val body = entry.body) {
                        is SessionEventBody.UserMessage -> body.payload.text
                        else -> entry.text
                    }
                    if (words.isNotEmpty()) {
                        Text(words, Modifier.padding(horizontal = Theme.Space.page, vertical = Theme.Space.tight), style = SystemFont.body, color = Theme.ink)
                    }
                }
            }
        }
    }
}
