package com.junbingao.remotecontrol.android.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.AgentLogo
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.FieldLabel
import com.junbingao.remotecontrol.android.design.FontScope
import com.junbingao.remotecontrol.android.design.Label
import com.junbingao.remotecontrol.android.design.LaptopGlyph
import com.junbingao.remotecontrol.android.design.LaptopShape
import com.junbingao.remotecontrol.android.design.OnlineDot
import com.junbingao.remotecontrol.android.design.PrimaryButtonStyle
import com.junbingao.remotecontrol.android.design.SettingsFooter
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActionRole
import com.junbingao.remotecontrol.android.system.Alert
import com.junbingao.remotecontrol.android.system.AlertAction
import com.junbingao.remotecontrol.android.system.BarIconButton
import com.junbingao.remotecontrol.android.system.BarTextButton
import com.junbingao.remotecontrol.android.system.BottomBar
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.android.system.RowStyle
import com.junbingao.remotecontrol.android.system.Sheet
import com.junbingao.remotecontrol.android.system.SwipeAction
import com.junbingao.remotecontrol.android.system.Toggle
import kotlin.math.roundToInt

/**
 * Screens of the iPhone's reference set redrawn from the design system's primitives, with the
 * demo's own words and values, so the system pieces — bars, lists, switches, alerts, sheets,
 * swipes — can be laid beside the iPhone's pictures and measured. They are not the app's screens,
 * which the feature ports build; they stand in for them only as far as the pieces need.
 */
private val sessionRow = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp)
private val settingsRow = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp)

/** `UsersView` (testUsersScreenListsAccountsAndAddsOne__77-users-screen). */
@Composable
fun UsersReplica(deleting: Boolean = false) {
    val attention = Theme.attention
    NavigationScreen(
        L10n.string("Users"),
        bottomBar = {
            BottomBar {
                Button(onClick = {}, style = PrimaryButtonStyle()) { Label(L10n.string("Add user"), Sf.plus) }
            }
        },
    ) { insets ->
        InsetGroupedList(Modifier.fillMaxSize(), contentPadding = insets.padding()) {
            section(
                key = "registration",
                footer = { SettingsFooter(L10n.string("Anyone with the gateway address can create an account")) },
            ) {
                row(style = RowStyle(insets = settingsRow)) {
                    FontScope(Theme.Text.label) { Toggle(L10n.string("Registration"), isOn = false, onChange = {}) }
                }
            }
            section(key = "accounts", header = { FieldLabel("Accounts") }) {
                for ((name, line, seen) in listOf(
                    Triple("admin", listOf("Admin · Active", "3 devices"), "2m"),
                    Triple("alice", listOf("Member · Active", "1 device"), "2d"),
                    Triple("bob", listOf("Member · Disabled"), "never"),
                )) {
                    row(key = name, style = RowStyle(insets = sessionRow, separator = false)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.medium)) {
                            Column(Modifier.weight(1f).alignByBaseline(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(name, style = Theme.Text.label, color = Theme.ink, lineLimit = 1)
                                Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
                                    Text(line[0], style = Theme.Text.meta, color = if (name == "bob") attention else Theme.inkSecondary)
                                    if (line.size > 1) {
                                        Text("·", style = Theme.Text.meta, color = Theme.inkSecondary)
                                        Text(line[1], style = Theme.Text.meta, color = Theme.inkSecondary)
                                    }
                                }
                            }
                            Text(seen, Modifier.alignByBaseline(), style = Theme.Text.meta, color = Theme.inkSecondary, lineLimit = 1)
                        }
                    }
                }
            }
        }
    }
    Alert(
        deleting,
        L10n.string("Delete account"),
        onDismiss = {},
        message = "Delete alice and its 1 device? The token stops working and its sessions leave this gateway. The machine keeps its agents and transcripts.",
        actions = listOf(
            AlertAction(L10n.string("Delete account"), ActionRole.destructive),
            AlertAction(L10n.string("Cancel"), ActionRole.cancel),
        ),
    )
}

/** One demo machine, as the iPhone's device list shows it. */
data class ReplicaDevice(
    val id: String,
    val name: String,
    val trailing: String,
    val online: Boolean,
    val platform: String,
    val agents: List<String>,
    val notice: String? = null,
)

val demoDevices = listOf(
    ReplicaDevice("mac-studio-office", "mac-studio-office", "18 ms", true, "macOS", listOf("claude", "codex", "grok", "pi")),
    ReplicaDevice("macbook-air", "macbook-air", "41 ms", true, "macOS", listOf("claude", "grok"), "Update failed · the device did not come back"),
    ReplicaDevice("ci-runner-01", "ci-runner-01", "1h", false, "Linux", listOf("codex")),
)

/** `DevicesView` (testDeviceRowNamesTheMachineOnceAndDrawsItsAgentsAsLogos__59-device-rows). */
@Composable
fun DevicesReplica(revoking: Boolean = false, adding: Boolean = false) {
    val grey = Theme.inkSecondary
    val danger = Theme.danger
    NavigationScreen(
        L10n.string("Devices"),
        trailing = {
            BarIconButton(Sf.line3HorizontalDecrease, L10n.string("Platform"), onClick = {}, tint = Theme.inkSecondary, width = 48.dp)
        },
        bottomBar = {
            BottomBar {
                Button(onClick = {}, style = PrimaryButtonStyle()) { Label(L10n.string("Add device"), Sf.plus) }
            }
        },
    ) { insets ->
        InsetGroupedList(Modifier.fillMaxSize(), contentPadding = insets.padding()) {
            section(key = "devices") {
                for (device in demoDevices) {
                    row(
                        key = device.id,
                        style = RowStyle(insets = sessionRow, separator = false),
                        tag = "device.${device.id}",
                        swipeActions = listOf(
                            SwipeAction(L10n.string("Revoke"), Sf.trash, danger) {},
                            SwipeAction(L10n.string("Show quota"), Sf.gaugeWithDotsNeedle33percent, grey) {},
                            SwipeAction(L10n.string("Rename"), Sf.pencil, grey) {},
                        ),
                        contextMenu = listOf(
                            MenuItem.Action(L10n.string("Rename"), Sf.pencil),
                            MenuItem.Action(L10n.string("Show quota"), Sf.gaugeWithDotsNeedle33percent),
                            MenuItem.Action(L10n.string("Revoke"), Sf.trash, role = ActionRole.destructive),
                        ),
                    ) { DeviceRowReplica(device) }
                }
            }
        }
    }
    Alert(
        revoking,
        L10n.string("Revoke device"),
        onDismiss = {},
        message = L10n.string(
            "Revoke %@? Its token stops working and its sessions leave this gateway. The machine keeps its agents and transcripts.",
            "macbook-air",
        ),
        actions = listOf(
            AlertAction(L10n.string("Revoke device"), ActionRole.destructive),
            AlertAction(L10n.string("Cancel"), ActionRole.cancel),
        ),
    )
    Sheet(adding, onDismiss = {}) { AddDeviceSheetReplica() }
}

@Composable
private fun DeviceRowReplica(device: ReplicaDevice) {
    Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        LaptopGlyph(Modifier.alignBy { (it.measuredHeight * LaptopShape.baselineFraction).roundToInt() })
        Column(Modifier.alignBy { it.firstBaselineOrTop() }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row {
                Text(device.name, Modifier.weight(1f).alignByBaseline(), style = Theme.Text.title, color = Theme.ink, lineLimit = 1)
                Text(device.trailing, Modifier.alignByBaseline(), style = Theme.Text.caption, color = Theme.inkSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                OnlineDot(device.online)
                Text("${if (device.online) "online" else "offline"} · ${device.platform}", style = Theme.Text.meta, color = Theme.inkSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
                for (agent in device.agents) AgentLogo(agent, size = Theme.Mark.control)
            }
            device.notice?.let { Text(it, style = Theme.Text.caption, color = Theme.danger) }
        }
    }
}

private fun androidx.compose.ui.layout.Measured.firstBaselineOrTop(): Int =
    this[androidx.compose.ui.layout.FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0

/** The top of `AddDeviceSheet` (testDevicesTabShowsPairingSheet__05-add-device). */
@Composable
private fun AddDeviceSheetReplica() {
    NavigationScreen(
        L10n.string("Add device"),
        displayMode = TitleDisplayMode.inline,
        showsBack = false,
        leading = { BarTextButton(L10n.string("Cancel"), {}) },
        trailing = { BarTextButton(L10n.string("Done"), {}, enabled = false, prominent = true) },
    ) { insets ->
        Text(
            L10n.string("Run one command on the machine where your agents live. It dials out to the gateway, so nothing is exposed on the host."),
            Modifier.padding(insets.padding()).padding(start = 20.dp, end = 20.dp, top = 10.dp),
            style = SystemFont.subheadline,
            color = Theme.inkSecondary,
        )
    }
}

/** The visible part of `SettingsView` (testSettingsHeaderAndVersionsLineNameTheAccountAndTheBuild__ios-round43-settings). */
@Composable
fun SettingsReplica() {
    NavigationScreen(L10n.string("Settings")) { insets ->
        InsetGroupedList(Modifier.fillMaxSize(), contentPadding = insets.padding()) {
            // `canvasRow()`: the header is a section's one row, on the canvas, with no insets.
            section(key = "identity") {
                row(style = RowStyle(insets = PaddingValues(0.dp), background = Color.Transparent, separator = false)) { IdentityReplica() }
            }
            section(key = "account", header = { FieldLabel("Account") }) {
                row(style = RowStyle(insets = PaddingValues(0.dp), separator = false)) {
                    SettingsRowReplica(L10n.string("Users"), L10n.string("Accounts on this gateway, and whether anyone can create one."), chevron = true)
                    SettingsRowReplica(
                        L10n.string("Sign out"),
                        L10n.string("Cached sessions and drafts leave this device. Nothing changes on your machines."),
                        danger = true,
                    )
                }
            }
            section(key = "away", header = { FieldLabel("While you're away") }) {
                row(style = RowStyle(insets = PaddingValues(0.dp), separator = false)) {
                    SettingsToggleReplica(L10n.string("Notify me"), L10n.string("Which device and session needs you, and nothing else."), false)
                    SettingsToggleReplica(
                        L10n.string("Resume after the limit resets"),
                        L10n.string("When Claude Code or Codex stops at a usage limit, the device continues the session a minute after the limit resets."),
                        true,
                    )
                }
            }
        }
    }
}

@Composable
private fun IdentityReplica() {
    Row(
        Modifier.padding(vertical = Theme.Space.small),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(Theme.ink, CircleShape), contentAlignment = Alignment.Center) {
            Text("AD", style = SystemFont.headline, color = Theme.onAccent)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("admin", style = Theme.Text.title, color = Theme.ink, lineLimit = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
                Text("Admin", style = Theme.Text.meta, color = Theme.inkSecondary)
                Text("·", style = Theme.Text.meta, color = Theme.inkSecondary)
                OnlineDot(online = true)
                Text(L10n.string("Demo"), style = Theme.Text.meta, color = Theme.inkSecondary)
            }
        }
    }
}

@Composable
private fun SettingsRowReplica(title: String, sentence: String, chevron: Boolean = false, danger: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Theme.Text.label, color = if (danger) Theme.danger else Theme.ink)
            Text(sentence, style = Theme.Text.caption, color = Theme.inkSecondary)
        }
        if (chevron) Icon(Sf.chevronRight, font = SystemFont.footnote.weight(FontWeight.SemiBold), tint = Theme.inkTertiary)
    }
}

@Composable
private fun SettingsToggleReplica(title: String, sentence: String, on: Boolean) {
    Toggle(title, on, {}, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Theme.Text.label, color = Theme.ink)
            Text(sentence, style = Theme.Text.caption, color = Theme.inkSecondary)
        }
    }
}
