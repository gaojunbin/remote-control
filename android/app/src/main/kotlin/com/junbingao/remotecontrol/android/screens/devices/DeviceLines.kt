package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.CodeText
import com.junbingao.remotecontrol.android.design.OnlineDot
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState
import com.junbingao.remotecontrol.core.state.DeviceLine
import com.junbingao.remotecontrol.core.state.DeviceUpdate

/** The dot, the state and the platform. It is the second line of a device's row and the first line of its page, written once so the two never drift. */
@Composable
fun DeviceStatusLine(device: Device, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        OnlineDot(online = device.online, updating = device.updateState == DeviceUpdateState.updating)
        Text(DeviceLine.status(device), style = Theme.Text.meta, color = Theme.inkSecondary)
    }
}

/**
 * `docs/DESIGN.md` § "The device row": the hostname and the architecture are facts someone opens a
 * machine's page to check, so they are drawn there and on no row.
 */
@Composable
fun DeviceFactsLine(device: Device, modifier: Modifier = Modifier) {
    CodeText(DeviceLine.facts(device), modifier, font = Theme.Text.metaMono)
}

/**
 * `docs/DESIGN.md` § "A device keeps itself current": the one thing an app says about a machine's
 * client, on its row and on its page alike. A device the gateway is keeping current says nothing,
 * so this line is drawn only where there is a notice to draw (A36).
 */
@Composable
fun DeviceUpdateLine(notice: DeviceUpdate.Notice, modifier: Modifier = Modifier) {
    Text(
        DeviceUpdateText.line(notice),
        modifier.testTag("device.updateNotice"),
        style = Theme.Text.caption,
        color = if (notice.isFailure) Theme.danger else Theme.inkSecondary,
    )
}

/**
 * `docs/DESIGN.md` § "An update names its version": what a device says about an update, written
 * once so the row, the page and the confirmation can never tell three different stories about the
 * same wheel.
 */
object DeviceUpdateText {
    /** The notice itself. There is no wording for a current device: the gateway keeps it current and the app says nothing (A36). */
    fun line(notice: DeviceUpdate.Notice): String = when (notice) {
        DeviceUpdate.Notice.Updating -> L10n.string("Updating…")
        is DeviceUpdate.Notice.Failed -> L10n.string("Update failed · %@", notice.message)
    }

    /** Why Retry update cannot act, said the same way wherever the action is drawn disabled. */
    fun reason(block: DeviceUpdate.Block): String = when (block) {
        DeviceUpdate.Block.offline -> L10n.string("This device is offline.")
        DeviceUpdate.Block.noServedBuild -> L10n.string("This gateway is not serving a client build.")
    }

    /** The confirmation, which names the machine and what it would land on. */
    fun confirmation(name: String, servedVersion: String?): String {
        if (servedVersion == null) {
            return L10n.string("Update %@ to the gateway's client? Its service restarts; sessions it drives are stopped.", name)
        }
        return L10n.string("Update %@ to %@? Its service restarts; sessions it drives are stopped.", name, servedVersion)
    }
}

val DeviceUpdate.Notice.isFailure: Boolean get() = this is DeviceUpdate.Notice.Failed
