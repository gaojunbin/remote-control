package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform

/**
 * The Devices screen's filter (owner's ruling, 2026-09-18): the same control the Sessions screen
 * has for agents, top right, narrowing the list to the machines on one platform. A view of the list
 * rather than a setting, so it is not remembered; the choices are the platforms actually present,
 * in the order the list first shows them.
 */
object DeviceFilter {
    /** The platforms the list can be narrowed to, each once, first seen first. */
    fun platforms(devices: List<Device>): List<DevicePlatform> = devices.map { it.platform }.distinct()

    /** The devices left once the filter is applied; null means all of them. */
    fun apply(devices: List<Device>, platform: DevicePlatform?): List<Device> {
        if (platform == null) return devices
        return devices.filter { it.platform == platform }
    }
}
