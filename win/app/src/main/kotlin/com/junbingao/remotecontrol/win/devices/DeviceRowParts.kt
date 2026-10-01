package com.junbingao.remotecontrol.win.devices

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState
import com.junbingao.remotecontrol.win.design.AgentLogo
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.OnlineDot
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.device-glyph`: one computer glyph per device, whatever its platform — a minimal outline laptop
 * in the ink, sized to the title (`docs/DESIGN.md` § "The device row"). It is what keeps two rows
 * apart now that nothing is drawn between them.
 */
@Composable
internal fun DeviceGlyph() {
    Icon(LucideIcon.laptopMinimal, size = DeviceGlyph.side, strokeWidth = 1.5f, color = Palette.ink)
}

internal object DeviceGlyph {
    /** `--device-glyph`, which the agents on a narrow screen indent past. */
    val side = 20.dp
}

/**
 * `.device-main`: the name once, the status line, and the third line only while an update runs or
 * has failed (A36) — or, for four seconds, why a click opened nothing (A38).
 */
@Composable
internal fun DeviceRowMain(
    device: Device,
    sessionCount: Int,
    notice: DeviceUpdateWords.Notice?,
    note: String?,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    VStack(modifier, spacing = 0.dp, alignment = Alignment.Start) {
        Text(device.name, css(FontSize.fs15, weight = FontWeight.SemiBold, lineHeight = 1.4f, tracking = -0.01f), lineLimit = 1)
        DeviceMetaLine(device, sessionCount, wide, Modifier.padding(top = 1.dp))
        if (notice != null) RowLine(notice.text, if (notice.failed) Palette.danger else Palette.inkSecondary)
        if (note != null) RowLine(note, Palette.attention, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun RowLine(text: String, ink: Color, modifier: Modifier = Modifier) {
    Text(text, css(FontSize.fs12, lineHeight = 1.45f), modifier.padding(top = 1.dp), color = ink)
}

/**
 * `.device-meta`: the online dot with its word and the platform as a word, then the session count
 * and the latency or last seen. The two halves share a line where they fit and part where they do
 * not; the dot between them is drawn on a wide screen only.
 */
@Composable
internal fun DeviceMetaLine(device: Device, sessionCount: Int, wide: Boolean, modifier: Modifier = Modifier) {
    WrapRow(spacing = Space.sp2, lineSpacing = 2.dp, modifier = modifier) {
        HStack(spacing = Space.sp2) {
            OnlineDot(device.online, pulses = device.updateState == DeviceUpdateState.updating)
            Text(
                "${if (device.online) S.devices.online else S.devices.offline} · ${S.platformLabel(device.platform.rawValue)}",
                css(FontSize.fs12, lineHeight = 1.45f),
                color = Palette.inkSecondary,
                lineLimit = 1,
            )
        }
        HStack(spacing = Space.sp2) {
            if (wide) Text("·", css(FontSize.fs12, lineHeight = 1.45f), color = Palette.lineStrong)
            Text(reach(device, sessionCount), css(FontSize.fs12, lineHeight = 1.45f), color = Palette.inkTertiary, lineLimit = 1)
        }
    }
}

private fun reach(device: Device, sessionCount: Int): String {
    val count = if (sessionCount > 0) S.devices.sessionsCount(sessionCount) else S.devices.noSessions
    val seen = if (device.online) {
        Format.latency(device.latencyMS?.toDouble())
    } else {
        S.devices.lastSeen(Format.relativeTime(device.lastSeen))
    }
    return "$count · $seen"
}

/**
 * `.device-agents`: what the device found, as logos alone, evenly spaced and with no name or
 * version beside them; each names its agent for a reader. The logo is one em of the strip's 16 px.
 * The row's own target lies over the strip, as the web's stretched link lies over it, so the
 * pointer never reaches a logo to name it.
 */
@Composable
internal fun DeviceAgentStrip(agents: List<AgentInfo>, modifier: Modifier = Modifier) {
    WithForeground(Palette.inkSecondary) {
        if (agents.isEmpty()) {
            Hint(S.devices.noAgents, modifier)
        } else {
            HStack(modifier, spacing = Space.sp3) {
                for (agent in agents) {
                    AgentLogo(
                        agent.agent,
                        size = FontSize.fs16,
                        modifier = Modifier.semantics {
                            contentDescription = S.agentLabel(agent.agent)
                            role = Role.Image
                        },
                    )
                }
            }
        }
    }
}
