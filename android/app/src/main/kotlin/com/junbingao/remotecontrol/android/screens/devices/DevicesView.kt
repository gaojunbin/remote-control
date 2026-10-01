package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.EmptyStateView
import com.junbingao.remotecontrol.android.design.Label
import com.junbingao.remotecontrol.android.design.PrimaryButtonStyle
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.navigation.LocalNavigator
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.screens.sessions.FilterMenu
import com.junbingao.remotecontrol.android.screens.sessions.barBacking
import com.junbingao.remotecontrol.android.screens.sessions.rowHeight
import com.junbingao.remotecontrol.android.screens.sessions.sessionRowLayout
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActionRole
import com.junbingao.remotecontrol.android.system.Alert
import com.junbingao.remotecontrol.android.system.AlertAction
import com.junbingao.remotecontrol.android.system.AlertTextField
import com.junbingao.remotecontrol.android.system.BottomBar
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.android.system.ListMetrics
import com.junbingao.remotecontrol.android.system.LocalBottomBarReach
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.android.system.RowStyle
import com.junbingao.remotecontrol.android.system.Sheet
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import com.junbingao.remotecontrol.core.state.DeviceFilter
import com.junbingao.remotecontrol.core.state.DeviceLine
import com.junbingao.remotecontrol.core.state.trimmed
import kotlinx.coroutines.CancellationException

/**
 * The machines this gateway knows about, and how to add another one.
 *
 * Amendment A38, rule 20: the row's own tap opens a shell on the machine, and its swipe and its
 * menu hold the rest — Rename · Retry update (only while one has failed) · Show quota · Revoke — in
 * the order the web uses, so nothing is reachable on one app and not the other (`docs/DESIGN.md`
 * § "Devices" and § "A device has a page, and a device row opens a terminal").
 */
@Composable
fun DevicesView() {
    val model = LocalAppModel.current
    val navigator = LocalNavigator.current
    var isAdding by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Device?>(null) }
    var newName by remember { mutableStateOf("") }
    var revoking by remember { mutableStateOf<Device?>(null) }
    var retrying by remember { mutableStateOf<Device?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    // The platform the list is narrowed to; a view of the list, not a setting.
    var platformFilter by rememberSaveable(stateSaver = PlatformSaver) { mutableStateOf<DevicePlatform?>(null) }
    // Why the last tap opened nothing, and on which row. A machine that is offline or offers no
    // terminal says so where it stands rather than pushing a screen that would only say it again.
    var refusal by rememberSaveable(stateSaver = Refusal.Saver) { mutableStateOf<Refusal?>(null) }

    val devices = model.connection.devices
    val shown = DeviceFilter.apply(devices, platform = platformFilter)
    val list = rememberLazyListState()
    val actions = DeviceRowActions(
        servedBuild = model.connection.config.servedBuild,
        tints = DeviceRowActions.Tints(rename = Theme.inkSecondary, retry = Theme.accent, quota = Theme.inkSecondary, revoke = Theme.danger),
        rename = { device ->
            renaming = device
            newName = device.name
        },
        retry = { device -> retrying = device },
        showQuota = { device -> navigator?.push(DeviceRoute.Page(device.deviceID)) },
        revoke = { device -> revoking = device },
    )

    // Rule 20: the row's tap opens a shell where one can be opened, and says why where it cannot.
    // The reason stands beside the row for a moment rather than in an alert: nothing went wrong,
    // and nothing needs dismissing.
    fun open(device: Device) {
        when (val outcome = DeviceTap.outcome(device)) {
            DeviceTap.Outcome.Terminal -> {
                refusal = null
                navigator?.push(DeviceRoute.Terminal(device.deviceID))
            }
            is DeviceTap.Outcome.Refused -> refusal = Refusal(device.deviceID, outcome.reason)
        }
    }

    NavigationScreen(
        L10n.string("Devices"),
        listState = list,
        trailing = { PlatformFilterMenu(devices, platformFilter) { platformFilter = it } },
        bottomBar = {
            BottomBar(Modifier.barBacking(down = LocalBottomBarReach.current)) {
                Button(
                    onClick = { isAdding = true },
                    Modifier.testTag("devices.add"),
                    enabled = !(model.isDemo && model.connection.api == null),
                    style = PrimaryButtonStyle(),
                ) { Label(L10n.string("Add device"), Sf.plus) }
            }
        },
    ) { insets ->
        InsetGroupedList(Modifier.fillMaxSize().testTag("devices.list"), list, insets.padding()) {
            section(key = "devices") {
                for (device in shown) {
                    // Rule 20: the row itself opens a shell on the machine. It is a button and not a
                    // link because the tap does not always lead anywhere — an offline machine, or one
                    // that offers no terminal, answers in place. One swipe carries every action, the
                    // first listed nearest the edge, so the row reads Rename · Retry update · Show
                    // quota · Revoke from left to right.
                    row(
                        key = device.deviceID,
                        style = RowStyle.sessionRowLayout,
                        onClick = { open(device) },
                        swipeActions = DeviceRowAction.menu(device).reversed().map { actions.swipe(it, device) },
                        contextMenu = DeviceRowAction.menu(device).map { actions.menuItem(it, device) },
                        tag = "device.${device.deviceID}",
                    ) { DeviceRow(device, localError = model.deviceUpdateError(device.deviceID)) }

                    // The answer to a tap that opened nothing, under the row it belongs to: a line of
                    // its own, so the row's own reading is still the machine and its state.
                    val reason = refusal?.takeIf { it.deviceID == device.deviceID }?.reason
                    if (reason != null) {
                        row(key = "${device.deviceID}.refusal", style = RefusalRow) {
                            Text(
                                reason,
                                Modifier.rowHeight(top = 0.dp, bottom = 14.dp).testTag("device.terminalRefusal"),
                                style = Theme.Text.caption,
                                color = Theme.inkSecondary,
                            )
                        }
                    }
                }
                val filter = platformFilter
                if (filter != null && shown.isEmpty() && devices.isNotEmpty()) {
                    row(key = "filtered", style = ClearRow) {
                        Text(
                            L10n.string("No %@ devices", DeviceLine.platformName(filter)),
                            Modifier.rowHeight(top = ClearInset, bottom = ClearInset).testTag("devices.platformFilter.empty"),
                            style = Theme.Text.meta,
                            color = Theme.inkSecondary,
                        )
                    }
                }
                if (devices.isEmpty()) {
                    row(key = "empty", style = ClearRow) {
                        EmptyStateView(
                            "desktopcomputer",
                            L10n.string("No devices yet"),
                            L10n.string("Run one command on the machine where your agents live. It dials out to the gateway; nothing is exposed on the host."),
                        )
                    }
                }
                error?.let { message ->
                    row(key = "error", style = ClearRow.copy(separator = true)) {
                        Text(message, Modifier.rowHeight(top = ClearInset, bottom = ClearInset), style = SystemFont.footnote, color = Theme.danger)
                    }
                }
            }
        }
    }

    // Each of these reads the row it acts on while the tap is still being handled: dismissing the
    // alert clears the state, and it can do so before the work started here gets to run.
    fun rename() {
        val device = renaming ?: return
        val api = model.connection.api ?: return
        val name = newName.trimmed
        renaming = null
        // The line belongs to the attempt being made, not to the screen: one failure must not
        // outlive it and sit under a later success.
        error = null
        model.perform {
            try {
                api.renameDevice(device.deviceID, name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = connection.message(failure)
            }
        }
    }

    fun revoke() {
        val device = revoking ?: return
        val api = model.connection.api ?: return
        revoking = null
        error = null
        model.perform {
            try {
                api.revokeDevice(device.deviceID)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = connection.message(failure)
            }
        }
    }

    // A refused update is the row's own news, not the gateway's: nothing started, so no
    // `device.updated` will ever carry it.
    fun retry() {
        val device = retrying ?: return
        retrying = null
        model.perform { updateDevice(device) }
    }

    Sheet(isAdding, onDismiss = { isAdding = false }) { AddDeviceSheet(dismiss = { isAdding = false }) }
    Alert(
        renaming != null,
        L10n.string("Rename device"),
        onDismiss = { renaming = null },
        actions = listOf(
            AlertAction(L10n.string("Cancel"), ActionRole.cancel, tag = "alert.cancel") { renaming = null },
            AlertAction(L10n.string("Save"), tag = "device.rename.save") { rename() },
        ),
        textField = AlertTextField(L10n.string("Name"), newName, { newName = it }, tag = "device.rename.name"),
    )
    Alert(
        retrying != null,
        L10n.string("Update device"),
        onDismiss = { retrying = null },
        message = DeviceUpdateText.confirmation(retrying?.name ?: L10n.string("This device"), model.connection.config.servedVersion),
        actions = listOf(
            AlertAction(L10n.string("Cancel"), ActionRole.cancel, tag = "alert.cancel") { retrying = null },
            AlertAction(L10n.string("Update"), tag = "device.update.confirm") { retry() },
        ),
    )
    Alert(
        revoking != null,
        L10n.string("Revoke device"),
        onDismiss = { revoking = null },
        message = L10n.string(
            "Revoke %@? Its token stops working and its sessions leave this gateway. The machine keeps its agents and transcripts.",
            revoking?.name ?: L10n.string("This device"),
        ),
        actions = listOf(
            AlertAction(L10n.string("Cancel"), ActionRole.cancel, tag = "alert.cancel") { revoking = null },
            AlertAction(L10n.string("Revoke device"), ActionRole.destructive, tag = "device.revoke.confirm") { revoke() },
        ),
    )
}

/**
 * All, then the platforms the list actually contains — the same control the Sessions screen has
 * for agents (`docs/DESIGN.md` § "Devices can be filtered by platform"). The chosen platform's word
 * stands beside the glyph, so the narrowed list says what it is narrowed to.
 */
@Composable
private fun PlatformFilterMenu(devices: List<Device>, platformFilter: DevicePlatform?, choose: (DevicePlatform?) -> Unit) {
    val options = DeviceFilter.platforms(devices)
    if (options.isEmpty()) return
    fun choice(platform: DevicePlatform?, label: String): MenuItem = MenuItem.Action(
        label,
        symbol = if (platformFilter == platform) Sf.checkmark else null,
        checked = platformFilter == platform,
        tag = "devices.platformFilter.${platform?.rawValue ?: "all"}",
    ) { choose(platform) }
    FilterMenu(
        items = listOf(choice(null, L10n.string("All devices"))) + options.map { choice(it, DeviceLine.platformName(it)) },
        narrowed = platformFilter != null,
        description = L10n.string("Filter by platform"),
        value = platformFilter?.let(DeviceLine::platformName) ?: L10n.string("All devices"),
        tag = "devices.platformFilter",
        choice = platformFilter?.let { platform -> { Text(DeviceLine.platformName(platform), style = Theme.Text.meta) } },
    )
}

/** Why a tap opened nothing, and on which row. */
private data class Refusal(val deviceID: String, val reason: String) {
    companion object {
        val Saver = listSaver<Refusal?, String>(
            save = { refusal -> refusal?.let { listOf(it.deviceID, it.reason) } ?: emptyList() },
            restore = { saved -> if (saved.size == 2) Refusal(saved[0], saved[1]) else null },
        )
    }
}

private val PlatformSaver = listSaver<DevicePlatform?, String>(
    save = { platform -> listOfNotNull(platform?.rawValue) },
    restore = { saved -> saved.firstOrNull()?.let(::DevicePlatform) },
)

/** `listRowInsets(top: 0, leading: medium, bottom: 14, trailing: medium)` on the row's surface, no separator. */
private val RefusalRow = RowStyle(
    insets = PaddingValues(start = Theme.Space.medium, top = 0.dp, end = Theme.Space.medium, bottom = 14.dp),
    separator = false,
)

/** A row on the page rather than on the card: `listRowBackground(Color.clear)`, at UIKit's own insets. */
private val ClearRow = RowStyle(background = Color.Transparent, separator = false)
private val ClearInset = ListMetrics.defaultInsets.calculateTopPadding()
