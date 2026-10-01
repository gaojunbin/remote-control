package com.junbingao.remotecontrol.win.app

import com.junbingao.remotecontrol.win.platform.AppData
import com.junbingao.remotecontrol.win.platform.AppTray
import com.junbingao.remotecontrol.win.platform.DpapiSecretVault
import com.junbingao.remotecontrol.win.platform.Host
import com.junbingao.remotecontrol.win.platform.MemorySecretVault
import com.junbingao.remotecontrol.win.platform.SecretVault
import com.junbingao.remotecontrol.win.platform.Toasts
import com.junbingao.remotecontrol.win.platform.TrayToasts

/**
 * The platform services one run of the app is built on, chosen once from its launch options: the
 * app model (stage 2) takes them from here. An `--ephemeral` run, and a run anywhere but Windows,
 * keeps the token in memory; the app on Windows seals it with DPAPI in its own data folder.
 */
class AppServices(val options: LaunchOptions, val vault: SecretVault, val toasts: Toasts) {
    companion object {
        fun make(options: LaunchOptions, tray: AppTray?): AppServices = AppServices(
            options = options,
            vault = if (options.ephemeral || !Host.isWindows) MemorySecretVault() else DpapiSecretVault(AppData.secrets),
            toasts = TrayToasts.make(tray),
        )
    }
}
