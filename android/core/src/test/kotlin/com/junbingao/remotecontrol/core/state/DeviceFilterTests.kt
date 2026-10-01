package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Devices screen's platform filter (owner's ruling, 2026-09-18). */
class DeviceFilterTests {
    private fun device(id: String, platform: DevicePlatform): Device =
        Device(deviceID = id, name = id, platform = platform, hostname = "$id.local", arch = "arm64",
               clientVersion = "1.4.4", online = true, lastSeen = 0, createdAt = 0)

    /** The choices are the platforms present, each once, first seen first. */
    @Test
    fun platforms() {
        val devices = listOf(device("a", DevicePlatform.macos), device("b", DevicePlatform.linux),
                             device("c", DevicePlatform.macos), device("d", DevicePlatform("freebsd")))
        assertEquals(listOf(DevicePlatform.macos, DevicePlatform.linux, DevicePlatform("freebsd")),
                     DeviceFilter.platforms(devices))
        assertTrue(DeviceFilter.platforms(emptyList()).isEmpty())
    }

    /** Nil keeps every device; a platform keeps its own and no other. */
    @Test
    fun apply() {
        val devices = listOf(device("a", DevicePlatform.macos), device("b", DevicePlatform.linux),
                             device("c", DevicePlatform.macos))
        assertEquals(listOf("a", "b", "c"), DeviceFilter.apply(devices, platform = null).map { it.deviceID })
        assertEquals(listOf("b"), DeviceFilter.apply(devices, platform = DevicePlatform.linux).map { it.deviceID })
        assertEquals(listOf("a", "c"), DeviceFilter.apply(devices, platform = DevicePlatform.macos).map { it.deviceID })
        assertTrue(DeviceFilter.apply(devices, platform = DevicePlatform("freebsd")).isEmpty())
    }
}
