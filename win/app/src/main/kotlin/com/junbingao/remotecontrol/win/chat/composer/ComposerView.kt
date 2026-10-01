package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.strings.S

/** The composer of one conversation. A placeholder until the composer feature replaces it. */
@Composable
fun ComposerView(chat: ChatStore) {
    Hint(S.composer.placeholder, Modifier.fillMaxWidth().padding(Space.sp4))
}
