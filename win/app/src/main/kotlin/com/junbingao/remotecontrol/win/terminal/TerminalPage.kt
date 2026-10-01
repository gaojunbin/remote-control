package com.junbingao.remotecontrol.win.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.layout.PageHead
import com.junbingao.remotecontrol.win.strings.S

/**
 * `/devices/:deviceId/terminal` (A38), drawn over the whole window. A placeholder until the
 * settings feature replaces it.
 */
@Composable
fun TerminalPage(deviceId: String) {
    Box(Modifier.fillMaxSize().background(Palette.canvas), contentAlignment = Alignment.TopStart) {
        PageHead(S.terminal.title, modifier = Modifier.padding(Space.sp6))
    }
}
