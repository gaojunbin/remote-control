package com.junbingao.remotecontrol.win.devices.adddevice

import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.win.shared.Format

/**
 * How far the gateway's clock is from this computer's, as the web's `clockSkewMs`: read off every
 * `hello`, so a pairing code's `expires_at` — a gateway timestamp — is counted down against the
 * gateway's clock rather than a local one that may be wrong.
 */
class GatewayClock {
    var skew: Long = 0
        private set

    /** A `hello` without `server_time` decodes it as 0, which says nothing. */
    fun receive(frame: AppFrame) {
        if (frame !is AppFrame.Hello) return
        skew = if (frame.hello.serverTime > 0) frame.hello.serverTime - Format.nowMillis else 0
    }

    /** Now, on the gateway's clock. */
    val now: Long get() = Format.nowMillis + skew
}
