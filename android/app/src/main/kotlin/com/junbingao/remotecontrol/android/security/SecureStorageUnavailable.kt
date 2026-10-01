package com.junbingao.remotecontrol.android.security

import com.junbingao.remotecontrol.android.strings.L10n

/**
 * The one failure secret storage reports, as RCCore's `TransportError.secureStorageUnavailable`
 * is: the phone is locked, or its secure hardware did not answer. The message is computed when
 * it is read, so it is in the language the reader has chosen by then.
 */
class SecureStorageUnavailable(cause: Throwable? = null) : Exception(cause) {
    override val message: String
        get() = L10n.string("Could not reach the keychain. Unlock this device and try again.")
}
