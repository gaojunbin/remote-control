package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.state.InstalledApp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Amendment A31: the oldest build of each separately installed app this gateway still works
 * with.
 *
 * It rides on `GET /api/health`, `GET /api/config` and `hello`. The health route is the one that
 * matters most: it is unauthenticated, so an app too old for a gateway is stopped at the sign-in
 * form rather than after it. A gateway that sends nothing states no requirement, which is what
 * every gateway older than the amendment does.
 */
@Serializable
data class AppsInfo(
    val ios: AppSupport? = null,
    /** Amendment A45: the Mac app's own entry. Only the Mac app reads it, and the Mac app reads nothing else. */
    val macos: AppSupport? = null,
    /** Amendment A46: the Android app's own entry, read by the Android app alone. */
    val android: AppSupport? = null,
    /** Amendment A46: the Windows app's own entry, read by the Windows app alone. */
    val windows: AppSupport? = null,
) {
    /** The entry the running app measures itself against. Absent means the gateway asks nothing of that app. */
    fun support(app: InstalledApp): AppSupport? = when (app) {
        InstalledApp.ios -> ios
        InstalledApp.macos -> macos
        InstalledApp.android -> android
        InstalledApp.windows -> windows
    }
}

/** What one app has to be, and where a newer build of it is. */
@Serializable
data class AppSupport(
    /** `major.minor.patch`. Anything else compares as zero, which is no bar. */
    @SerialName("minimum_version") val minimumVersion: String = "",
    /** An app store, TestFlight or a download page, when the operator named one. Always https. */
    @SerialName("update_url") val updateURL: String? = null,
)
