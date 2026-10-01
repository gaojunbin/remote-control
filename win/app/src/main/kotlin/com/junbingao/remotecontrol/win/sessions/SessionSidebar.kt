package com.junbingao.remotecontrol.win.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.win.design.LayoutSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.layout.PageHead
import com.junbingao.remotecontrol.win.strings.S

/** The conversation page's session list. A placeholder until the lists feature replaces it. */
@Composable
fun SessionSidebar(deviceId: String, sessionId: String) {
    Box(Modifier.width(LayoutSize.sidebarW).fillMaxHeight().background(Palette.canvas), contentAlignment = Alignment.TopStart) {
        PageHead(S.sessions.title, modifier = Modifier.padding(Space.sp4))
    }
}
