package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.android.design.LaptopGlyph
import com.junbingao.remotecontrol.android.design.OnlineDot
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.navigation.LocalNavigator
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.shell.ConnectionSummary
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.core.state.DeviceLine

/**
 * Placeholder for `android-lists`, which ports the iPhone's `DevicesView` here. Until then it
 * lists the machines — the laptop, the name, the state — and a tap does what rule 20 says
 * ([DeviceTap]): a shell on a device that offers one, pushed on this tab's stack.
 */
@Composable
fun DevicesView() {
    val model = LocalAppModel.current
    val navigator = LocalNavigator.current
    val list = rememberLazyListState()
    NavigationScreen(
        L10n.string("Devices"),
        listState = list,
        top = { ConnectionSummary(model.connection.phase, model.isDemo) { model.perform { connection.reconnect() } } },
    ) { insets ->
        InsetGroupedList(Modifier.fillMaxSize(), list, insets.padding()) {
            section(key = "devices") {
                for (device in model.connection.devices) {
                    val tap = {
                        if (DeviceTap.outcome(device) == DeviceTap.Outcome.Terminal) navigator?.push(DeviceRoute.Terminal(device.deviceID))
                    }
                    row(key = device.deviceID, onClick = tap, tag = "device.${device.deviceID}") {
                        Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small), verticalAlignment = Alignment.CenterVertically) {
                            LaptopGlyph()
                            Column {
                                Text(device.name, style = Theme.Text.title, color = Theme.ink, lineLimit = 1)
                                Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
                                    OnlineDot(device.online)
                                    Text(DeviceLine.status(device), style = Theme.Text.meta, color = Theme.inkSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
