package com.junbingao.remotecontrol.win.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.layout.PageHead
import com.junbingao.remotecontrol.win.strings.S

/**
 * The conversation, `/sessions/:deviceId/:sessionId`, drawn over the whole window. A placeholder
 * until the chat feature replaces it.
 */
@Composable
fun ChatPage(deviceId: String, sessionId: String) {
    val model = LocalAppModel.current
    val title = model.connection.session(deviceID = deviceId, sessionID = sessionId)?.let(S::sessionTitle)
    Box(Modifier.fillMaxSize().background(Palette.canvas), contentAlignment = Alignment.TopStart) {
        PageHead(title ?: S.sessions.untitled, modifier = Modifier.padding(Space.sp6))
    }
}
