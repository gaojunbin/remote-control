package com.junbingao.remotecontrol.android.screens

import com.junbingao.remotecontrol.android.screens.devices.DeviceRowAction
import com.junbingao.remotecontrol.android.screens.devices.DeviceTap
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Rule 20: what a device row's tap does, and the order of its actions. */
class DeviceRouteTest {
    private fun device(online: Boolean = true, terminal: Boolean? = true, update: DeviceUpdateState = DeviceUpdateState.idle) =
        Device(deviceID = "d", online = online, terminal = terminal, updateState = update)

    @Test
    fun aTapOpensAShellOnlyWhereThereIsOne() {
        assertEquals(DeviceTap.Outcome.Terminal, DeviceTap.outcome(device()))
        assertEquals(DeviceTap.Outcome.Refused("This device is offline."), DeviceTap.outcome(device(online = false)))
        assertEquals(DeviceTap.Outcome.Refused("This device does not offer a terminal."), DeviceTap.outcome(device(terminal = null)))
    }

    @Test
    fun retryUpdateIsOfferedOnlyWhileAnUpdateHasFailed() {
        assertEquals(listOf(DeviceRowAction.rename, DeviceRowAction.showQuota, DeviceRowAction.revoke), DeviceRowAction.menu(device()))
        assertEquals(DeviceRowAction.entries.toList(), DeviceRowAction.menu(device(update = DeviceUpdateState.failed)))
    }
}
