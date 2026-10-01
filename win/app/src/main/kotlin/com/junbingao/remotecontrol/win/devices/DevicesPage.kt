package com.junbingao.remotecontrol.win.devices

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.EmptyState
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.card
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.surface
import com.junbingao.remotecontrol.win.devices.adddevice.AddDeviceModal
import com.junbingao.remotecontrol.win.devices.adddevice.AddDevicePairing
import com.junbingao.remotecontrol.win.layout.PageHead
import com.junbingao.remotecontrol.win.strings.S

/**
 * `/devices` — `web/src/features/devices/DevicesPage.tsx`: the title with how many devices are
 * connected, Add device, and one row per enrolled machine in name order on one surface; or the
 * empty card for an account with none.
 */
@Composable
fun DevicesPage() {
    val model = LocalAppModel.current
    val stage = DevicesPage.Stage.of(LocalPreviewStage.current)
    val adding = remember { mutableStateOf<AddDevicePairing?>(null) }
    val renaming = remember { mutableStateOf<Device?>(null) }
    val revoking = remember { mutableStateOf<Device?>(null) }
    // A36: the only update an app asks for is a retry of one that failed.
    val retrying = remember { mutableStateOf<Device?>(null) }
    val devices = DeviceOrder.byName(model.connection.devices)
    val counts = DeviceOrder.sessionCounts(model.connection.sessions)
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        PageHead(S.devices.title, hint = S.devices.subtitleCount(devices.count { it.online }, devices.size)) {
            Btn(S.devices.add, icon = LucideIcon.plus, variant = ButtonVariant.primary) {
                AddDeviceModal.open(model, adding, AddDevicePairing())
            }
        }
        if (devices.isEmpty()) {
            EmptyState(S.devices.emptyHint, title = S.devices.empty, modifier = Modifier.card())
        } else {
            VStack(Modifier.fillMaxWidth().surface(), spacing = 0.dp) {
                for (device in devices) {
                    key(device.deviceID) {
                        DeviceRow(
                            device = device,
                            sessionCount = counts[device.deviceID] ?: 0,
                            servedBuild = model.connection.config.servedBuild,
                            updateError = model.deviceUpdateErrors[device.deviceID],
                            menuOpen = DevicesPage.stagedMenu(stage, devices) == device.deviceID,
                            refusesOnAppear = DevicesPage.stagedRefusal(stage, devices) == device.deviceID,
                            onRename = { renaming.value = device },
                            onRetryUpdate = { retrying.value = device },
                            onRevoke = { revoking.value = device },
                        )
                    }
                }
            }
        }
    }
    AddDeviceModal(adding)
    DeviceDialogs(renaming, revoking, retrying)
    LaunchedEffect(Unit) { DevicesPage.openStaged(model, stage, devices, adding, renaming, revoking, retrying) }
}

// Preview stages

object DevicesPage {
    /** What a render asks the page to show that normally takes a click. */
    enum class Stage(val rawValue: String) {
        menu("devices.menu"),
        failedMenu("devices.menu.failed"),
        refusal("devices.refusal"),
        rename("devices.rename"),
        revoke("devices.revoke"),
        retry("devices.retry"),
        add("devices.add"),
        addManual("devices.add.manual");

        companion object {
            fun of(rawValue: String?): Stage? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /** The row whose menu a render draws open: the first, or the first whose update failed. */
    internal fun stagedMenu(stage: Stage?, devices: List<Device>): String? = when (stage) {
        Stage.menu -> devices.firstOrNull()?.deviceID
        Stage.failedMenu -> devices.firstOrNull { it.updateState == DeviceUpdateState.failed }?.deviceID
        else -> null
    }

    /** The first row whose click can open nothing, for the line that says why. */
    internal fun stagedRefusal(stage: Stage?, devices: List<Device>): String? =
        if (stage == Stage.refusal) devices.firstOrNull { !(it.online && it.offersTerminal) }?.deviceID else null

    internal fun openStaged(
        model: WinAppModel,
        stage: Stage?,
        devices: List<Device>,
        adding: MutableState<AddDevicePairing?>,
        renaming: MutableState<Device?>,
        revoking: MutableState<Device?>,
        retrying: MutableState<Device?>,
    ) {
        when (stage ?: return) {
            Stage.rename -> renaming.value = devices.firstOrNull()
            Stage.revoke -> revoking.value = devices.firstOrNull()
            Stage.retry -> retrying.value = devices.firstOrNull { it.updateState == DeviceUpdateState.failed }
            Stage.add, Stage.addManual -> AddDeviceModal.open(model, adding, AddDevicePairing().apply { manual = stage == Stage.addManual })
            Stage.menu, Stage.failedMenu, Stage.refusal -> {}
        }
    }
}
