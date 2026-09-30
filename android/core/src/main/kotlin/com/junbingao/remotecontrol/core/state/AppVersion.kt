package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AppsInfo
import com.junbingao.remotecontrol.core.swiftInt
import com.junbingao.remotecontrol.core.trimmingWhitespaces
import java.net.URI

/**
 * A `major.minor.patch` build number, compared part by part.
 *
 * `"1.2.3" < "1.10.0"`, because ten is a number here and not a character. Missing parts are zero
 * and a part that is not a number is zero, so reading a version can never fail and never throws:
 * the worst a malformed string can do is compare low.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    constructor(text: String) : this(parts(text))

    private constructor(parts: List<Int>) : this(
        parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 },
    )

    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    private companion object {
        fun parts(text: String): List<Int> = text.split(".").map { it.trimmingWhitespaces().swiftInt() ?: 0 }
    }
}

/**
 * This build's own version, as the gateway's minimum is measured against it.
 *
 * RCCore reads it from the app bundle. The Kotlin core has no bundle to read, so each app states
 * its version once at startup — `AppBuild.version = BuildConfig.VERSION_NAME` on Android, the
 * packaged version on Windows — and everything else reads [version] as RCCore reads its own.
 */
object AppBuild {
    /**
     * The version this source tree ships, which the release bump moves with every component.
     * Everything with no app to ask reads it: the tests, and the demo gateway, whose served
     * client is this app's own version because a round ships all components together.
     */
    const val shipped = "1.12.0"

    /** The running app's version: [shipped] until the app states its own at startup. */
    @Volatile
    var version: String = shipped
}

/**
 * Amendments A45 and A46: which separately installed app this build is. Each one reads its own
 * entry of `apps` and never another's, so the minimums can move apart.
 */
enum class InstalledApp(val rawValue: String) {
    ios("ios"),
    macos("macos"),
    android("android"),
    windows("windows");

    companion object {
        operator fun invoke(rawValue: String): InstalledApp? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * Amendment A31: this build is older than the gateway will talk to.
 *
 * Nothing else in the app is reachable while one of these is set, so the rule that produces it is
 * pure and small enough to read in one go.
 */
data class AppUpdateRequirement(
    /** What this app is. */
    val current: AppVersion,
    /** The oldest build the gateway works with. */
    val minimum: AppVersion,
    /** Where a newer build is, when the operator named a place. Only https. */
    val updateURL: URI? = null,
) {
    companion object {
        /**
         * The rule: null when the gateway states no minimum, when this build meets it, and when
         * this build is newer. Only "below" produces a requirement. The iPhone app is the default
         * reader, as in RCCore; every other app names itself, as the Mac app does.
         */
        fun of(apps: AppsInfo?, app: InstalledApp = InstalledApp.ios,
               current: String = AppBuild.version): AppUpdateRequirement? {
            val support = apps?.support(app) ?: return null
            val minimum = AppVersion(support.minimumVersion)
            val version = AppVersion(current)
            if (version >= minimum) return null
            return AppUpdateRequirement(current = version, minimum = minimum, updateURL = updateLink(support.updateURL))
        }

        /**
         * A link the app will open, or nothing. The schema says https and the app checks it too: a
         * blocking screen is no place to follow an odd scheme.
         */
        private fun updateLink(text: String?): URI? {
            val url = text?.let { runCatching { URI(it) }.getOrNull() } ?: return null
            return if (url.scheme?.lowercase() == "https") url else null
        }
    }
}
