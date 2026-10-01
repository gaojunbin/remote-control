package com.junbingao.remotecontrol.win.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.GroupTitle
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.VStackScope
import com.junbingao.remotecontrol.win.design.surface

/** `SettingsGroup.tsx`: a caption on the canvas and its rows on one soft surface. Nothing else. */
@Composable
fun SettingsGroup(title: String, rows: @Composable VStackScope.() -> Unit) {
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        GroupTitle(title)
        VStack(Modifier.fillMaxWidth().surface(), spacing = 0.dp, content = rows)
    }
}
