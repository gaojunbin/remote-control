package com.junbingao.remotecontrol.android.navigation

import android.net.Uri

/**
 * `remotecontrol://session?device=<id>&id=<session_id>`, the iPhone's link into a conversation,
 * which a notification's tap and `adb shell am start -d` both deliver.
 */
data class SessionLink(val deviceID: String, val sessionID: String) {
    /** The link as the iPhone writes it. */
    fun uri(): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(HOST)
        .appendQueryParameter("device", deviceID)
        .appendQueryParameter("id", sessionID)
        .build()

    companion object {
        const val SCHEME = "remotecontrol"
        const val HOST = "session"

        /** The link a URI carries, or null for anything else — a stranger's scheme, a missing id. */
        fun parse(uri: Uri?): SessionLink? {
            if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
            val device = uri.getQueryParameter("device")?.takeIf { it.isNotBlank() } ?: return null
            val session = uri.getQueryParameter("id")?.takeIf { it.isNotBlank() } ?: return null
            return SessionLink(device, session)
        }
    }
}
