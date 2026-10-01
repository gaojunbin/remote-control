package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform

/**
 * The words a machine is described by: the line its row carries and the line its page carries.
 *
 * `docs/DESIGN.md` § "The device row" (owner's ruling, 2026-09-17): the row says the state and the
 * platform as a word, and nothing else — the name is the title above it, and the hostname repeating
 * that name one line down was the noise the ruling removed. The hostname and the architecture stay
 * on the machine's own page, which is where someone goes to check them.
 *
 * The platform is a product name, so it is mapped rather than translated, and an id this build has
 * never heard of prints as itself: a gateway that grows a third platform needs no new app.
 */
object DeviceLine {
    const val separator = " · "

    private val platforms = mapOf("macos" to "macOS", "linux" to "Linux")

    /** The platform as a word, never the raw id. */
    fun platformName(platform: DevicePlatform): String = platforms[platform.rawValue] ?: platform.rawValue

    /** The row's line, beside its dot: what the machine is doing and what it runs. It names neither the machine nor any agent on it. */
    fun status(device: Device): String =
        listOf(if (device.online) L10n.string("online") else L10n.string("offline"), platformName(device.platform))
            .joinToString(separator)

    /** The page's line, under the machine's name: what it calls itself and what it is built on. Either half is dropped where the device reported none. */
    fun facts(device: Device): String =
        listOf(device.hostname, device.arch).filter { it.isNotEmpty() }.joinToString(separator)
}
