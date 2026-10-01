package com.junbingao.remotecontrol.win.app

import java.io.ByteArrayOutputStream

/**
 * The web's routes (`web/src/App.tsx`), as places in the app. There is no pairing-link route: a
 * host's QR code encodes a URL, which opens in the phone app or a browser (`docs/DESIGN.md`
 * § "The Windows app", after the Mac's).
 */
sealed interface Route {
    /** `/` and every path no route claims: decides where an open lands. */
    data object Landing : Route
    data object Login : Route
    data object Devices : Route

    /** `/devices/:deviceId` (A33). */
    data class Device(val id: String) : Route

    /** `/devices/:deviceId/terminal` (A38), full window. */
    data class Terminal(val deviceId: String) : Route
    data object Sessions : Route

    /** `/sessions/:deviceId/:sessionId`, full window. */
    data class Chat(val deviceId: String, val sessionId: String) : Route
    data object Settings : Route

    /** `/users` (A24), the admin's. */
    data object Users : Route

    /** The web path of this place, which is how a scenario or a test can name it. */
    val path: String
        get() = when (this) {
            Landing -> "/"
            Login -> "/login"
            Devices -> "/devices"
            is Device -> "/devices/${encode(id)}"
            is Terminal -> "/devices/${encode(deviceId)}/terminal"
            Sessions -> "/sessions"
            is Chat -> "/sessions/${encode(deviceId)}/${encode(sessionId)}"
            Settings -> "/settings"
            Users -> "/users"
        }

    /** Drawn inside the topbar layout, as the web's `AppLayout` routes are. */
    val isInLayout: Boolean
        get() = when (this) {
            Devices, is Device, Sessions, Settings, Users -> true
            Landing, Login, is Terminal, is Chat -> false
        }

    /**
     * One of the three tabs the topbar marks, when this place is under one. `NavLink` marks a tab
     * for its path and every path below it.
     */
    val tab: Route?
        get() = when (this) {
            Devices, is Device, is Terminal -> Devices
            Sessions, is Chat -> Sessions
            Settings -> Settings
            else -> null
        }

    companion object {
        /** The place a web path names; a path no route claims lands, as `*` does. */
        fun of(path: String): Route {
            val parts = path.split('/').filter { it.isNotEmpty() }.map(::decode)
            return when {
                parts.size == 1 -> when (parts[0]) {
                    "login" -> Login
                    "devices" -> Devices
                    "sessions" -> Sessions
                    "settings" -> Settings
                    "users" -> Users
                    else -> Landing
                }
                parts.size == 2 && parts[0] == "devices" -> Device(parts[1])
                parts.size == 3 && parts[0] == "devices" && parts[2] == "terminal" -> Terminal(parts[1])
                parts.size == 3 && parts[0] == "sessions" -> Chat(parts[1], parts[2])
                else -> Landing
            }
        }

        /** What a path segment may hold as it is: Foundation's `urlPathAllowed`, less the slash. */
        private const val allowed = "!$&'()*+,-.:;=@_~"

        private fun encode(part: String): String {
            val out = StringBuilder()
            for (byte in part.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xFF).toChar()
                if (c.isLetterOrDigit() && c.code < 0x80 || c in allowed) out.append(c) else out.append("%%%02X".format(byte.toInt() and 0xFF))
            }
            return out.toString()
        }

        /** `removingPercentEncoding`, which leaves a segment it cannot read as it is. */
        private fun decode(part: String): String {
            if ('%' !in part) return part
            val bytes = ByteArrayOutputStream()
            var index = 0
            while (index < part.length) {
                val c = part[index]
                if (c == '%') {
                    val value = part.substring(index + 1, minOf(index + 3, part.length)).toIntOrNull(16)
                    if (value == null || index + 3 > part.length) return part
                    bytes.write(value)
                    index += 3
                } else {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    index++
                }
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }
}
