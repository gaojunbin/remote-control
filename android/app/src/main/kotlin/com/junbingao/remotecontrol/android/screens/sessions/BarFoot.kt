package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.ui.unit.dp

/**
 * iOS 26's navigation bar reaches a little below its row of buttons, or below its large title: a
 * screen's content laid out against the bar's safe area starts there, as the device page's first
 * line, the terminal's status line, the Sessions screen's connection line and a sheet's scroll view
 * do (`80-device-page-checking`, `ios-round42-terminal`, `31-origin-and-legend`, `05-add-device`).
 * The shell's `NavigationScreen` hands its content the row's own foot, so a screen whose content
 * starts at the bar adds this; a list's first card already stands where the iPhone's does.
 */
object BarFoot {
    val height = 10.17.dp
}
