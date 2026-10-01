package com.junbingao.remotecontrol.win.shared

import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SpeedChange

/**
 * `web/src/features/chat/sessionOptions.ts`: what `session.set` can carry from the composer. A21
 * adds the speed tier.
 *
 * Absent and null are different for the speed: `SpeedChange.Standard` is the standard tier, which
 * is a change, while null — leaving it out — is not.
 */
data class SessionOptions(
    val model: String? = null,
    val permissionMode: String? = null,
    val effort: String? = null,
    val speed: SpeedChange? = null,
) {
    /** The session as it will be once the device accepts the patch. */
    fun applied(to: Session): Session {
        var next = to
        if (model != null) next = next.copy(model = model)
        if (permissionMode != null) next = next.copy(permissionMode = permissionMode)
        if (effort != null) next = next.copy(effort = effort)
        if (speed != null) next = next.copy(speed = speed.id)
        return next
    }
}
