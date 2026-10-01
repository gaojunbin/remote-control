package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.core.state.DeviceLine

/**
 * Placeholder for `android-lists`, which ports the iPhone's `DeviceDetailView` (the agents and
 * their quotas, A33) here: the machine's name and its facts until then.
 */
@Composable
fun DeviceDetailView(deviceID: String) {
    val model = LocalAppModel.current
    val device = model.connection.device(deviceID)
    NavigationScreen(device?.name ?: deviceID, displayMode = TitleDisplayMode.inline) { insets ->
        InsetGroupedList(Modifier.fillMaxSize(), contentPadding = insets.padding()) {
            if (device != null) {
                section(key = "facts") {
                    row(key = "facts") { Text(DeviceLine.facts(device), style = Theme.Text.meta, color = Theme.inkSecondary) }
                }
            }
        }
    }
}
