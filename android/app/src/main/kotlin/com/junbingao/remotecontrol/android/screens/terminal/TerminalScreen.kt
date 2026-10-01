package com.junbingao.remotecontrol.android.screens.terminal

import androidx.compose.runtime.Composable
import com.junbingao.remotecontrol.android.navigation.HidesTabBar
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.shell.LocalAppModel

/**
 * Placeholder for `android-lists`, which ports the iPhone's `TerminalScreen` and `TerminalKeyBar`
 * here, on the foundation's `TerminalHost` (the emulator, `…android.terminal`) and the core's
 * `TerminalSession`. Until then: a full screen under the machine's name.
 */
@Composable
fun TerminalScreen(deviceID: String) {
    val model = LocalAppModel.current
    HidesTabBar()
    NavigationScreen(model.connection.device(deviceID)?.name ?: deviceID, displayMode = TitleDisplayMode.inline) { }
}
