package com.junbingao.remotecontrol.android.compare

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.pageBackground
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.navigation.HidesTabBar
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.system.BarIconButton
import com.junbingao.remotecontrol.android.system.SearchField

/** A conversation's bar (`02-chat`): back, the title inline, Stop in its glass circle. */
@Composable
fun ChatBarReplica() {
    HidesTabBar()
    NavigationScreen(
        "Fix flaky auth test",
        displayMode = TitleDisplayMode.inline,
        trailing = { BarIconButton(Sf.stopCircle, "Stop", onClick = {}, tint = Theme.ink) },
    ) { }
}

/** The archive's search while it is in use (`16-archive-search`): the field and its close button. */
@Composable
fun ArchiveSearchReplica() {
    Box(Modifier.fillMaxSize().pageBackground()) {
        SearchField("OTLP", {}, Modifier.padding(start = 16.dp, top = 62.dp, end = 16.dp), isActive = true, onCancel = {})
    }
}
