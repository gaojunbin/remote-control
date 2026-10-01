package com.junbingao.remotecontrol.win.sessions

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.DeviceGroup
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.ArchiveGroupHeader
import com.junbingao.remotecontrol.win.design.DeviceGroupHeader
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.card
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.surface

/**
 * One device's group on the Sessions page: its header with the online dot and the disclosure
 * chevron, then its active rows on one surface, then its own Archive, captioned "Archive · N" and
 * folded shut until the reader opens it. The choices are kept per device id in the store both lists
 * read.
 */
@Composable
internal fun SessionGroupSection(
    group: DeviceGroup,
    /** A preview stage's: the close question drawn open on this row. */
    asksToClose: String? = null,
) {
    val model = LocalAppModel.current
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        // A header's margin under it collapses into the gap between groups when nothing follows
        // it, so it is only kept above rows.
        DeviceGroupHeader(
            group.name, group.online, expanded = !group.collapsed,
            modifier = Modifier.padding(bottom = if (group.collapsed) 0.dp else Space.sp2),
        ) { model.sessions.toggleCollapsed(group.id) }
        if (!group.collapsed) {
            if (group.active.isNotEmpty()) Rows(group.active, group.online, asksToClose)
            if (group.archive.isNotEmpty()) Archive(group, asksToClose)
        }
    }
}

/** The Archive's 12 above it collapses into the header's 8 when no active row stands between them. */
@Composable
private fun Archive(group: DeviceGroup, asksToClose: String?) {
    val model = LocalAppModel.current
    VStack(
        Modifier.fillMaxWidth().padding(top = if (group.active.isEmpty()) Space.sp1 else Space.sp3),
        spacing = 0.dp,
        alignment = Alignment.Start,
    ) {
        ArchiveGroupHeader(
            group.archive.size, group.archiveExpanded,
            modifier = Modifier.padding(bottom = if (group.archiveExpanded) Space.sp2 else 0.dp),
        ) { model.sessions.toggleArchive(group.id) }
        if (group.archiveExpanded) Rows(group.archive, group.online, asksToClose)
    }
}

@Composable
private fun Rows(sessions: List<Session>, online: Boolean, asksToClose: String?) {
    VStack(Modifier.fillMaxWidth().surface(), spacing = 0.dp) {
        for (session in sessions) {
            key(session.id) { SessionRow(session, online, asksToClose = asksToClose == session.id) }
        }
    }
}

/** `.card.empty` on the Sessions page: the first line in the ink, and the hint under it only when there is no search to blame. */
@Composable
internal fun SessionsEmptyCard(title: String, hint: String?) {
    VStack(Modifier.fillMaxWidth().card().padding(vertical = Space.sp10, horizontal = Space.sp4), spacing = 0.dp) {
        Text(title, css(FontSize.fs14, weight = FontWeight.Medium), Modifier.padding(bottom = Space.sp1), color = Palette.ink, textAlign = TextAlign.Center)
        if (hint != null) Text(hint, css(FontSize.fs14), color = Palette.inkSecondary, textAlign = TextAlign.Center)
    }
}
