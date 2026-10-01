package com.junbingao.remotecontrol.win.app

import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.core.state.InstalledApp

/**
 * The core's diagnostic snapshot as this app writes it: the report names the app that wrote it
 * (round 56's ruling), and the platform is Windows where the iPhone's says iOS.
 */
fun WinAppModel.diagnosticReport(): String = settings.diagnosticReport(
    app = InstalledApp.windows,
    appVersion = AppBuild.version,
    platform = "Windows",
    osVersion = System.getProperty("os.version").orEmpty(),
    phase = connection.phase,
    deviceCount = connection.devices.size,
    sessionCount = connection.sessions.size,
    sttEnabled = connection.stt.enabled,
    isDemo = isDemo,
)
