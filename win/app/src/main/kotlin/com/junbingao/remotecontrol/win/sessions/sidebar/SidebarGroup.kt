package com.junbingao.remotecontrol.win.sessions.sidebar

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.DeviceGroup
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.design.ArchiveGroupHeader
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonConfiguration
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.DeviceGroupHeader
import com.junbingao.remotecontrol.win.design.DotSize
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.StatusDot
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.devices.ExactFrame
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import com.junbingao.remotecontrol.win.unseen.showsUnseenDot
import com.junbingao.remotecontrol.win.unseen.unseenTitle
import com.junbingao.remotecontrol.win.unseen.unseenValue

/**
 * One device's group in the chat sidebar: the same header as the Sessions page, raised 8 px into
 * the list, its rows, and its Archive indented under them.
 */
@Composable
internal fun SidebarGroup(group: DeviceGroup, activeKey: String) {
    val model = LocalAppModel.current
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        DeviceGroupHeader(
            group.name, group.online, expanded = !group.collapsed, leading = Space.sp2,
            // Its margin collapses into the gap between groups when nothing follows.
            modifier = Modifier.padding(bottom = if (group.collapsed) 0.dp else Space.sp1),
        ) { model.sessions.toggleCollapsed(group.id) }
        if (!group.collapsed) {
            for (session in group.active) key(session.id) { Item(session, group.online, activeKey) }
            if (group.archive.isNotEmpty()) {
                // 8 above it, which the header's 4 collapses into when no row stands between them.
                ArchiveGroupHeader(
                    group.archive.size, group.archiveExpanded, leading = Space.sp4,
                    modifier = Modifier.padding(
                        top = if (group.active.isEmpty()) Space.sp1 else Space.sp2,
                        bottom = if (group.archiveExpanded) Space.sp1 else 0.dp,
                    ),
                ) { model.sessions.toggleArchive(group.id) }
                if (group.archiveExpanded) {
                    for (session in group.archive) key(session.id) { Item(session, group.online, activeKey) }
                }
            }
        }
    }
}

@Composable
private fun Item(session: Session, online: Boolean, activeKey: String) {
    val model = LocalAppModel.current
    SidebarItem(session, online, active = session.id == activeKey, unseen = model.showsUnseenDot(session)) {
        model.router.go(Route.Chat(deviceId = session.deviceID, sessionId = session.sessionID))
    }
}

/**
 * `.sidebar-item`: the status dot, the title, and under it the folder and the time, whatever the
 * session is doing — the dot carries the state (`docs/DESIGN.md` § "The session row says where it
 * came from"). The row the conversation shows is one step further into the ink. The leading padding
 * is the gutter a red dot sits in (A47), kept by every row so the dot moves nothing when it comes
 * or goes; [unseen] is whether this row draws one.
 */
@Composable
internal fun SidebarItem(session: Session, online: Boolean, active: Boolean, unseen: Boolean, action: () -> Unit) {
    Button(action, Modifier.fillMaxWidth().unseenValue(unseen), style = SidebarItemStyle(active)) {
        HStack(Modifier.padding(start = SidebarGutter, end = Space.sp2), spacing = Space.sp2) {
            StatusDot(session.state, session.control, online = online)
            VStack(Modifier.weight(1f), spacing = 1.dp, alignment = Alignment.Start) {
                Text(
                    S.sessionTitle(session),
                    css(FontSize.fs14, weight = FontWeight.SemiBold, lineHeight = 1.4f, tracking = -0.01f),
                    Modifier.unseenTitle(unseen, reach = TitleReach, gutter = SidebarGutter),
                    lineLimit = 1,
                )
                Text(
                    "${Format.baseName(session.cwd)} · ${Format.relativeTime(session.updatedAt)}",
                    css(FontSize.fs12, lineHeight = 1.45f),
                    color = Palette.inkSecondary,
                    lineLimit = 1,
                )
            }
        }
    }
}

/** `.sidebar-item`'s leading padding: the gutter the red dot is centred in (A47). */
private val SidebarGutter = Space.sp5

/** From the row's edge to the title: its padding, the status dot and the gap after it. */
private val TitleReach = SidebarGutter + DotSize.statusDot + Space.sp2

private class SidebarItemStyle(private val active: Boolean) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val fill by animateColorAsState(
            when {
                active -> Palette.hoverSelected
                configuration.isHovered -> Palette.hover
                else -> Color.Transparent
            },
            Motion.ease(Motion.durFast, LocalReduceMotion.current),
        )
        ExactFrame(modifier.background(fill, RoundedCornerShape(10.dp)), height = 48.dp) { configuration.label() }
    }
}
