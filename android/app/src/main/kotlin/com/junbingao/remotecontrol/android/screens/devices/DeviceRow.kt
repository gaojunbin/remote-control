package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.AgentLogo
import com.junbingao.remotecontrol.android.design.LaptopGlyph
import com.junbingao.remotecontrol.android.design.LaptopShape
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.state.DeviceUpdate
import com.junbingao.remotecontrol.core.state.RelativeTime
import kotlin.math.roundToInt

/**
 * One machine, behind one glyph. `docs/DESIGN.md` § "The device row": the name and one number on
 * the first line, the dot with its state and platform on the second, the agents as their logos.
 * No status column, no rules, and nothing that repeats the name. A fourth line is drawn only where
 * an update is running or has failed — the version the machine runs is never on the row (A36).
 *
 * The row is one element for assistive technology, which the list row it stands in makes it.
 */
@Composable
fun DeviceRow(device: Device, localError: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        // The same glyph for every machine whatever its platform: a minimal outline laptop. The app
        // cannot tell a laptop from a desktop, and one honest mark beats a wrong guess. It is what
        // keeps two rows apart now that no separator is drawn between them. Its base line sits on
        // the title's first baseline, as the foot of a letter would.
        LaptopGlyph(Modifier.alignBy { (it.measuredHeight * LaptopShape.baselineFraction).roundToInt() })
        Column(Modifier.weight(1f).alignBy { it.firstBaselineOrTop() }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
                Text(device.name, Modifier.weight(1f).alignByBaseline(), style = Theme.Text.title, color = Theme.ink, lineLimit = 1)
                Text(trailing(device), Modifier.alignByBaseline(), style = Theme.Text.caption, color = Theme.inkSecondary)
            }
            DeviceStatusLine(device)
            if (device.availableAgents.isNotEmpty()) DeviceAgentsLine(device.availableAgents)
            DeviceUpdate.notice(device, localError = localError)?.let { DeviceUpdateLine(it) }
        }
    }
}

/** Latency while it answers, and how long ago it last did when it does not. */
internal fun trailing(device: Device): String {
    if (device.online) return device.latencyMS?.let { "$it ms" } ?: ""
    return RelativeTime.short(since = device.lastSeen)
}

/**
 * The agents a machine reported, as their logos and nothing else (`docs/DESIGN.md` § "The device
 * row"). A logo is a drawing, so each one carries its agent's name as its accessible label and the
 * row still reads aloud; the versions are on the machine's page, beside the accounts.
 */
@Composable
private fun DeviceAgentsLine(agents: List<AgentInfo>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        for (info in agents) {
            Box(Modifier.semantics { contentDescription = info.displayName }) {
                AgentLogo(info.agent, size = Theme.Mark.control)
            }
        }
    }
}

private fun Measured.firstBaselineOrTop(): Int = this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0
