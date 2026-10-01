package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.state.DeviceGroup

/**
 * The machine's name exactly as it reported it, its online dot, and a chevron. One tap folds the
 * whole group away, and that choice is remembered per machine.
 */
@Composable
fun DeviceHeader(group: DeviceGroup, toggle: () -> Unit) {
    val label = L10n.string("%@, %@", group.name, L10n.string(if (group.online) "online" else "offline"))
    val hint = L10n.string(if (group.collapsed) "Expands this device" else "Collapses this device")
    Button(
        onClick = toggle,
        // The tag before the semantics it would otherwise be cleared with, and the button's own
        // action restated, since a cleared node keeps only what is set on it.
        Modifier
            .fillMaxWidth()
            .testTag("sessions.device.${group.id}")
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = hint
                role = Role.Button
                onClick(label = hint) {
                    toggle()
                    true
                }
            },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (group.online) Theme.running else Theme.resting, CircleShape))
            Text(group.name, Modifier.weight(1f), style = Theme.Text.title, color = Theme.ink, lineLimit = 1)
            Chevron(open = !group.collapsed)
        }
    }
}

/**
 * The device's own Archive: what nothing owns any more, plus what was archived by hand. Collapsed
 * until it is asked for, or until a search finds something inside it. The row it stands in is the
 * button, so the whole row is the target.
 */
@Composable
fun ArchiveHeader(group: DeviceGroup, modifier: Modifier = Modifier) {
    val label = L10n.string("Archive, %lld sessions on %@", group.archive.size, group.name)
    val hint = L10n.string(if (group.archiveExpanded) "Collapses the archive" else "Expands the archive")
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = hint
            },
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(L10n.string("Archive · %lld", group.archive.size), Modifier.weight(1f), style = Theme.Text.meta, color = Theme.inkSecondary)
        Chevron(open = group.archiveExpanded)
    }
}

/** Open points down, folded points at the trailing edge. */
@Composable
private fun Chevron(open: Boolean) {
    Icon(
        Sf.chevronDown,
        Modifier.rotate(if (open) 0f else -90f),
        font = SystemFont.caption2.weight(FontWeight.SemiBold),
        tint = Theme.inkSecondary,
    )
}
