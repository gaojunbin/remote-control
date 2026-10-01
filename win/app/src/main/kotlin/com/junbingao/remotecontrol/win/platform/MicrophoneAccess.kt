package com.junbingao.remotecontrol.win.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * Whether Windows' privacy settings keep the microphone from desktop apps: the switch for the
 * whole device, the one for this account's apps, and the one for desktop apps, each stored as
 * `Value` = `Deny` under `CapabilityAccessManager\ConsentStore\microphone`. Anywhere but Windows,
 * and where the settings cannot be read, nothing says no.
 */
object MicrophoneAccess {
    private const val store = "Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\microphone"

    fun isDenied(): Boolean {
        if (!Host.isWindows) return false
        return runCatching {
            denied(WinReg.HKEY_LOCAL_MACHINE, store) ||
                denied(WinReg.HKEY_CURRENT_USER, store) ||
                denied(WinReg.HKEY_CURRENT_USER, "$store\\NonPackaged")
        }.getOrDefault(false)
    }

    private fun denied(root: WinReg.HKEY, key: String): Boolean =
        Advapi32Util.registryValueExists(root, key, "Value") &&
            Advapi32Util.registryGetStringValue(root, key, "Value").equals("Deny", ignoreCase = true)
}
