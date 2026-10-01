package com.junbingao.remotecontrol.win.devices

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.state.DeviceUpdate
import com.junbingao.remotecontrol.win.strings.S

/**
 * A36, in the web's words: `updateNotice` in `web/src/stores/devices.ts` and the two reasons the
 * row and the page give for a Retry they cannot send. The core's `DeviceUpdate` decides; this says
 * it.
 */
object DeviceUpdateWords {
    /** The third line's words, and whether they report a failure. */
    data class Notice(val text: String, val failed: Boolean)

    /**
     * The row's third line, or null when there is nothing to say — the ordinary case, because the
     * gateway brings every device to the wheel it serves on its own.
     */
    fun notice(device: Device, localError: String?): Notice? = when (val notice = DeviceUpdate.notice(device, localError)) {
        DeviceUpdate.Notice.Updating -> Notice(S.devices.updating, failed = false)
        is DeviceUpdate.Notice.Failed -> Notice(S.devices.updateFailed(notice.message), failed = true)
        null -> null
    }

    /** Why Retry update cannot be pressed, or null when it can. */
    fun retryBlocked(device: Device, servedBuild: String?): String? = when (DeviceUpdate.block(device, servedBuild)) {
        DeviceUpdate.Block.offline -> S.devices.deviceOffline
        DeviceUpdate.Block.noServedBuild -> S.devices.updateNoBuild
        null -> null
    }
}
