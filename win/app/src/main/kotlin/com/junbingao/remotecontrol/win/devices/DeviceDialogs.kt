package com.junbingao.remotecontrol.win.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.updateDevice
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FieldLabel
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WebField
import com.junbingao.remotecontrol.win.design.overlay.ConfirmDialog
import com.junbingao.remotecontrol.win.design.overlay.Modal
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The Devices page's three dialogs: the rename modal, and the Retry update and Revoke
 * confirmations. Each closes once its request went through and stays open when it was refused, as
 * the web's do.
 */
@Composable
internal fun DeviceDialogs(renaming: MutableState<Device?>, revoking: MutableState<Device?>, retrying: MutableState<Device?>) {
    val model = LocalAppModel.current
    var renameValue by remember(renaming.value?.deviceID) { mutableStateOf(renaming.value?.name ?: "") }
    var busy by remember { mutableStateOf(false) }

    fun rename() {
        val device = renaming.value ?: return
        val api = model.connection.api ?: return
        val name = renameValue.trimmed
        busy = true
        model.tasks.launch {
            try {
                // The gateway's `device.updated` carries the new name to the list.
                if (succeeded { api.renameDevice(device.deviceID, name) } && renaming.value?.deviceID == device.deviceID) {
                    renaming.value = null
                }
            } finally {
                busy = false
            }
        }
    }

    fun revoke() {
        val device = revoking.value ?: return
        val api = model.connection.api ?: return
        busy = true
        model.tasks.launch {
            try {
                // `device.removed` takes the row away.
                if (succeeded { api.revokeDevice(device.deviceID) } && revoking.value?.deviceID == device.deviceID) {
                    revoking.value = null
                }
            } finally {
                busy = false
            }
        }
    }

    val renamePresence = ListPresence.of(renaming)
    Modal(
        isPresented = renamePresence.isPresented,
        onDismiss = renamePresence.onDismiss,
        title = S.devices.renameTitle,
        width = 420.dp,
        footer = {
            Btn(S.common.cancel) { renaming.value = null }
            Disabled(renameValue.trimmed.isEmpty()) {
                Btn(S.common.save, variant = ButtonVariant.primary, busy = busy, action = ::rename)
            }
        },
    ) {
        VStack(spacing = 0.dp, alignment = Alignment.Start) {
            FieldLabel(S.devices.renameLabel)
            WebField(renameValue, { renameValue = it })
        }
    }
    RetryUpdateDialog(retrying)
    val revokePresence = ListPresence.of(revoking)
    ConfirmDialog(
        isPresented = revokePresence.isPresented,
        onDismiss = revokePresence.onDismiss,
        title = S.devices.revokeTitle,
        body = S.devices.revokeBody(revoking.value?.name ?: ""),
        confirmLabel = S.devices.revokeConfirm,
        danger = true,
        busy = busy,
        onConfirm = ::revoke,
    )
}

/**
 * A36: the one update a person asks for — a retry of one that failed — confirmed with the version
 * it would install. Shared by the Devices page and a device's own page.
 */
@Composable
internal fun RetryUpdateDialog(device: MutableState<Device?>) {
    val model = LocalAppModel.current
    var busy by remember { mutableStateOf(false) }

    // A refusal is the device's own words, which the row then shows; either way nothing is left
    // to confirm.
    fun retry() {
        val target = device.value ?: return
        if (model.connection.config.servedBuild == null) return
        busy = true
        model.tasks.launch {
            model.updateDevice(target)
            device.value = null
            busy = false
        }
    }

    val presence = ListPresence.of(device)
    ConfirmDialog(
        isPresented = presence.isPresented,
        onDismiss = presence.onDismiss,
        title = S.devices.updateTitle,
        body = S.devices.updateBody(device.value?.name ?: "", model.connection.config.servedVersion),
        confirmLabel = S.devices.updateConfirm,
        busy = busy,
        onConfirm = ::retry,
    )
}

/** `try? await`: whether the request went through, a cancellation going on as one. */
private suspend fun succeeded(request: suspend () -> Unit): Boolean = try {
    request()
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}
