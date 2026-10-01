package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `docs/DESIGN.md` § "The device row" (owner's ruling, 2026-09-17): what a machine's row says, and
 * what only its page says.
 */
class DeviceLineTests {
    private fun device(platform: DevicePlatform, online: Boolean = true, hostname: String = "mac-studio.local",
                       arch: String = "arm64"): Device =
        Device(deviceID = "d1", name = "mac-studio-office", platform = platform, hostname = hostname, arch = arch,
               clientVersion = "1.3.4", online = online, lastSeen = 0, createdAt = 0)

    /** A platform is a word, and an unknown one is printed as it arrived. */
    @Test
    fun platformName() {
        assertEquals("macOS", DeviceLine.platformName(DevicePlatform.macos))
        assertEquals("Linux", DeviceLine.platformName(DevicePlatform.linux))
        assertEquals("freebsd", DeviceLine.platformName(DevicePlatform("freebsd")))
    }

    /** The row's line is the state and the platform, and neither host nor chip. */
    @Test
    fun status() {
        assertEquals("online · macOS", DeviceLine.status(device(platform = DevicePlatform.macos)))
        assertEquals("offline · Linux", DeviceLine.status(device(platform = DevicePlatform.linux, online = false)))
        val line = DeviceLine.status(device(platform = DevicePlatform.macos))
        assertFalse(line.contains("mac-studio.local"))
        assertFalse(line.contains("arm64"))
        assertFalse(line.contains("macos"))
    }

    /** The page's line is the hostname and the architecture. */
    @Test
    fun facts() {
        assertEquals("ci-runner-01 · x86_64",
                     DeviceLine.facts(device(platform = DevicePlatform.linux, hostname = "ci-runner-01", arch = "x86_64")))
    }

    /** A device that reported neither half writes no separator at all. */
    @Test
    fun missingFacts() {
        assertEquals("", DeviceLine.facts(device(platform = DevicePlatform.macos, hostname = "", arch = "")))
        assertEquals("mac-studio.local", DeviceLine.facts(device(platform = DevicePlatform.macos, arch = "")))
    }
}
