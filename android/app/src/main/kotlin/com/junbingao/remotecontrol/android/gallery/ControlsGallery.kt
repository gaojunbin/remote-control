package com.junbingao.remotecontrol.android.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.AgentChip
import com.junbingao.remotecontrol.android.design.AppMark
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ChipButtonStyle
import com.junbingao.remotecontrol.android.design.CodeText
import com.junbingao.remotecontrol.android.design.EmptyStateView
import com.junbingao.remotecontrol.android.design.FieldLabel
import com.junbingao.remotecontrol.android.design.Label
import com.junbingao.remotecontrol.android.design.NoticeBanner
import com.junbingao.remotecontrol.android.design.OnlineDot
import com.junbingao.remotecontrol.android.design.PrimaryButtonStyle
import com.junbingao.remotecontrol.android.design.SessionOriginLabel
import com.junbingao.remotecontrol.android.design.StatusDot
import com.junbingao.remotecontrol.android.design.StatusLabel
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.ValueRow
import com.junbingao.remotecontrol.android.design.WorkingCircle
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n

/** Every control of `Controls.swift`, `ValueRow.swift` and `WorkingCircle.swift`, drawn as screens draw them. */
@Composable
internal fun ControlsGallery() {
    GalleryScaffold(GalleryPages.controls.title) {
        Specimen("Status dots: working, for you (breathing), live, error, off; device online, offline, updating") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Theme.running, pulses = false)
                StatusDot(Theme.attention, pulses = true)
                StatusDot(Theme.attention, pulses = false)
                StatusDot(Theme.danger, pulses = false)
                StatusDot(Theme.resting, pulses = false)
                OnlineDot(online = true)
                OnlineDot(online = false)
                OnlineDot(online = false, updating = true)
            }
            StatusLabel(Theme.attention, pulses = true, text = L10n.string("needs approval"), textColor = Theme.attention)
            SessionOriginLabel(Theme.running, pulses = false, origin = L10n.string("Remote Control"))
            SessionOriginLabel(Theme.attention, pulses = false, origin = L10n.string("Terminal"))
        }
        Specimen("Agent chips") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentChip("claude", "Claude Code")
                AgentChip("codex", "Codex")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentChip("grok", "Grok Build")
                AgentChip("pi", "pi")
                AgentChip("aider", "aider")
            }
        }
        Specimen("Field label, code text, value rows") {
            FieldLabel("Working directory") { Text("3", style = Theme.Text.meta, color = Theme.inkSecondary) }
            CodeText("/Users/me/dev/remote-control/gateway/rc_gateway/compat.py")
            Column {
                ValueRow("This app", value = "1.12.0")
                ValueRow("Gateway needs", value = "1.12.0", mono = true)
            }
        }
        Specimen("Buttons") {
            Button(onClick = {}, style = PrimaryButtonStyle()) { Label(L10n.string("Add device"), Sf.plus) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {}, style = ChipButtonStyle) { Label(L10n.string("Scan a code"), Sf.qrcodeViewfinder) }
                Button(onClick = {}, style = PrimaryButtonStyle(fullWidth = false)) { Text(L10n.string("Done")) }
                WorkingCircle(L10n.string("Finishing the transcript"))
            }
        }
        Specimen("Notice banners") {
            NoticeBanner(L10n.string("Delivery unconfirmed"), actionTitle = L10n.string("Retry"), action = {}, dismiss = {})
            NoticeBanner(
                "Paused by the usage limit · resumes 3:50 PM",
                tint = Theme.attention,
                actionTitle = L10n.string("Change"),
                action = {},
                secondaryActionTitle = L10n.string("Cancel"),
                secondaryAction = {},
            )
        }
        Specimen("Empty state and the app mark") {
            EmptyStateView("desktopcomputer", L10n.string("No devices yet"), L10n.string("Add a device first, then start a session on it."))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppMark()
                AppMark(62.dp)
            }
        }
    }
}
