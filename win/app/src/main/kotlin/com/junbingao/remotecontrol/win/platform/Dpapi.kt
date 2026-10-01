package com.junbingao.remotecontrol.win.platform

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt

/**
 * Windows' data protection (`CryptProtectData`) for the current user, with this app's own entropy
 * so another program of the same user cannot open the file by handing it to DPAPI alone, and never
 * a prompt: a sealed file that needs one is unavailable instead.
 */
object Dpapi : DpapiSecretVault.Protector {
    private val entropy = "com.junbingao.remotecontrol.win".toByteArray()

    override fun protect(data: ByteArray): ByteArray =
        Crypt32Util.cryptProtectData(data, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "Remote Control", null)

    override fun unprotect(data: ByteArray): ByteArray =
        Crypt32Util.cryptUnprotectData(data, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
}
