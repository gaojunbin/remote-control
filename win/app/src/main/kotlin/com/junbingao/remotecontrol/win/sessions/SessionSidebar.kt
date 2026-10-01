package com.junbingao.remotecontrol.win.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.IconBtn
import com.junbingao.remotecontrol.win.design.LayoutSize
import com.junbingao.remotecontrol.win.design.Mark
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.ThinScrollView
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.devices.DeviceOrder
import com.junbingao.remotecontrol.win.layout.LocalTrafficLightInset
import com.junbingao.remotecontrol.win.sessions.controls.ListSearchField
import com.junbingao.remotecontrol.win.sessions.drawer.NewSessionDrawer
import com.junbingao.remotecontrol.win.sessions.drawer.NewSessionForm
import com.junbingao.remotecontrol.win.sessions.sidebar.SidebarGroup
import com.junbingao.remotecontrol.win.strings.S

/**
 * The conversation page's session list — `web/src/features/chat/Sidebar.tsx`: the brand with New
 * session beside it, a search of its own, the sessions grouped by device with each device's Archive
 * folded under it, and a footer saying how many devices there are and how many sessions wait on the
 * reader. The groups follow the one rule every list follows, with the agent filter and the folds
 * the Sessions page shares; the search is this list's alone. New session here starts on the
 * conversation's own device.
 */
@Composable
fun SessionSidebar(deviceId: String, sessionId: String) {
    val model = LocalAppModel.current
    var query by remember { mutableStateOf("") }
    val creating = remember { mutableStateOf<NewSessionForm?>(null) }
    val sessions = model.connection.sessions
    val groups = SessionLayout.build(
        sessions = sessions, devices = model.connection.devices,
        agentFilter = model.sessions.agentFilter, query = query,
        collapsedDevices = model.sessions.collapsedDevices, archiveExpanded = model.sessions.expandedArchives,
    )

    // The web presets the drawer to the conversation's device only when that conversation is there
    // to have one.
    fun openNewSession() {
        val preset = if (model.connection.session(deviceID = deviceId, sessionID = sessionId) == null) null else deviceId
        creating.value = NewSessionForm(devices = DeviceOrder.online(model.connection.devices), preset = preset)
    }

    VStack(Modifier.width(LayoutSize.sidebarW).fillMaxHeight().background(Palette.canvas), spacing = 0.dp) {
        SidebarHead(onNewSession = ::openNewSession)
        ListSearchField(
            query, { query = it }, S.chat.searchPlaceholder, iconSize = 14.dp, height = 32.dp, maxWidth = null,
            modifier = Modifier.padding(start = Space.sp3, end = Space.sp3, bottom = Space.sp3),
        )
        ThinScrollView(modifier = Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = S.nav.sessions }) {
            VStack(
                Modifier.fillMaxWidth().padding(top = Space.sp1, start = Space.sp2, end = Space.sp2, bottom = Space.sp3),
                spacing = Space.sp5,
                alignment = Alignment.Start,
            ) {
                for (group in groups) key(group.id) { SidebarGroup(group, activeKey = "$deviceId/$sessionId") }
            }
        }
        Text(
            S.chat.sidebarFooter(model.connection.devices.size, SessionLayout.countWaiting(SessionLayout.unarchived(sessions))),
            css(FontSize.fs12),
            Modifier.fillMaxWidth().padding(vertical = Space.sp3, horizontal = Space.sp4),
            color = Palette.inkTertiary,
        )
    }
    NewSessionDrawer(creating)
}

/**
 * `.sidebar-head`: the brand, which goes to the Sessions page, and New session. The Mac's is the
 * window's top strip and starts after the traffic lights; the window here keeps Windows' title bar
 * above it, so it starts at the web's padding, and the wordmark gives way before the mark does
 * when there is no room for both.
 */
@Composable
private fun SidebarHead(onNewSession: () -> Unit) {
    val model = LocalAppModel.current
    val start = maxOf(Space.sp4, LocalTrafficLightInset.current)
    HStack(Modifier.fillMaxWidth().padding(start = start, top = Space.sp4, end = Space.sp3, bottom = Space.sp3), spacing = Space.sp2) {
        Button({ model.router.go(Route.Sessions) }, accessibilityLabel = S.productName) {
            FirstThatFits({ Brand(wordmark = true) }, { Brand(wordmark = false) })
        }
        Spacer(Modifier.weight(1f))
        IconBtn(LucideIcon.plus, size = 16.dp, label = S.chat.newSession, action = onNewSession)
    }
}

@Composable
private fun Brand(wordmark: Boolean) {
    HStack(spacing = Space.sp2) {
        Mark(size = 18.dp)
        if (wordmark) Text(S.productName, css(FontSize.fs15, weight = FontWeight.SemiBold, tracking = -0.01f), lineLimit = 1)
    }
}

/** SwiftUI's `ViewThatFits(in: .horizontal)` of two: the first when its own width fits, else the second. */
@Composable
private fun FirstThatFits(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Layout({
        first()
        second()
    }) { measurables, constraints ->
        val wide = measurables[0]
        val fits = !constraints.hasBoundedWidth || wide.maxIntrinsicWidth(Constraints.Infinity) <= constraints.maxWidth
        val placeable = (if (fits) wide else measurables[1]).measure(constraints.copy(minWidth = 0))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
