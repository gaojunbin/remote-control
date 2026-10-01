package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.trimmingWhitespaces

/**
 * The one line that closes the Settings screen.
 *
 * `docs/DESIGN.md` § "The Settings screen": `Remote Control 1.5.1 · Gateway 1.5.0 · Protocol v1`,
 * in the tertiary ink and not a group. Every number is read from the running code — the build, the
 * gateway's `hello`, the protocol this build speaks — so a line that is wrong is a bug and never a
 * typo. A gateway that has not said its version yet leaves its half out rather than printing an
 * empty one.
 */
object VersionsLine {
    fun text(app: String, gateway: String, protocolVersion: Int): String {
        val version = gateway.trimmingWhitespaces()
        if (version.isEmpty()) return L10n.string("Remote Control %@ · Protocol v%lld", app, protocolVersion)
        return L10n.string("Remote Control %@ · Gateway %@ · Protocol v%lld", app, version, protocolVersion)
    }
}
