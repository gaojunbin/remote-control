package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.runtime.Composable
import com.junbingao.remotecontrol.android.screens.terminal.TerminalScreen
import com.junbingao.remotecontrol.android.shell.TabRoot

/**
 * What the Devices tab's stack draws for a route — the iPhone's `NavigationStack(path:
 * $model.devicePath)` with `navigationDestination(for: DeviceRoute.self)`: the list at the root,
 * a machine's page, and a shell on it.
 */
@Composable
fun DevicesDestination(route: Any) {
    when (route) {
        is TabRoot -> DevicesView()
        is DeviceRoute.Page -> DeviceDetailView(deviceID = route.deviceID)
        is DeviceRoute.Terminal -> TerminalScreen(deviceID = route.deviceID)
    }
}
