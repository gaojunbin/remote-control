package com.junbingao.remotecontrol.win.devices.adddevice

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.FormError
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.overlay.Modal
import com.junbingao.remotecontrol.win.devices.ListsFeature
import com.junbingao.remotecontrol.win.devices.TextMeasure
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The frame handler the modal listens with while it is open. */
private const val TOKEN = "lists.pairing"

/**
 * `AddDeviceModal`: a 580 px modal over the Devices list, open while there is a pairing to show.
 * Opening one asks the gateway for a code and listens for its handshake; Cancel, Continue, Escape
 * and the backdrop all close it, and a code the device never claimed is given back.
 */
@Composable
internal fun AddDeviceModal(pairing: MutableState<AddDevicePairing?>) {
    val model = LocalAppModel.current
    val open = pairing.value
    Modal(
        isPresented = open != null,
        onDismiss = { AddDeviceModal.close(model, pairing) },
        title = S.pairing.title,
        width = 580.dp,
        footer = {
            Btn(S.common.cancel) { AddDeviceModal.close(model, pairing) }
            Disabled(open?.connected != true) {
                Btn(S.common.`continue`, variant = ButtonVariant.primary) { AddDeviceModal.close(model, pairing) }
            }
        },
    ) {
        if (open != null) AddDeviceBody(open)
    }
    // `.onChange(of: pairing, initial: true)`: a new visit starts as the modal shows it.
    LaunchedEffect(open) { if (open != null) AddDeviceModal.start(model, open) }
}

internal object AddDeviceModal {
    /** Listen for the visit's handshake and ask the gateway for its code. */
    fun start(model: WinAppModel, visit: AddDevicePairing) {
        model.connection.addFrameHandler(TOKEN) { frame -> visit.receive(frame) }
        model.tasks.launch { visit.request(model.connection.api) }
    }

    fun close(model: WinAppModel, slot: MutableState<AddDevicePairing?>) {
        val closing = slot.value ?: return
        slot.value = null
        model.connection.removeFrameHandler(TOKEN)
        val code = closing.unclaimedCode ?: return
        val api = model.connection.api ?: return
        model.tasks.launch {
            try {
                api.cancelPairing(code)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * What the modal says, top to bottom: one sentence, the one-liner with Copy and the code under it,
 * the live handshake, the scan flow, and Manual install.
 */
@Composable
internal fun AddDeviceBody(pairing: AddDevicePairing) {
    val model = LocalAppModel.current
    // `useNow(500)`: the countdown and the listening clock move twice a second.
    val tick by produceState(Format.nowMillis) {
        while (true) {
            delay(500)
            value = Format.nowMillis
        }
    }
    // Read again on every tick, as the Mac's `TimelineView` reads it: the code counts down.
    val now = remember(tick) { ListsFeature.clock(model).now }
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        Hint(S.pairing.intro, Modifier.widthIn(max = TextMeasure.ch(FontSize.fs13) * 46))
        // `.login-error`'s -6 top margin, collapsed into the intro's 16.
        if (pairing.failed) FormError(S.pairing.createFailed, Modifier.padding(top = 10.dp))
        PairCommandBox(
            pairing, now,
            newCode = { model.tasks.launch { pairing.request(model.connection.api) } },
            modifier = Modifier.padding(top = Space.sp4),
        )
        PairingSteps(pairing, elapsed = tick - pairing.openedAt, modifier = Modifier.padding(top = Space.sp4))
        PairScanSection(pairing, command = S.pairing.scanCommand(model.origin), modifier = Modifier.padding(top = Space.sp4))
        ManualInstall(pairing, origin = model.origin, modifier = Modifier.padding(top = Space.sp4))
    }
}
