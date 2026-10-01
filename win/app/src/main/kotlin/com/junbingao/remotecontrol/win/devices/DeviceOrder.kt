package com.junbingao.remotecontrol.win.devices

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.win.sessions.SessionLayout

/**
 * What `web/src/stores/devices.ts` keeps about the list itself: the devices in name order — the
 * web sorts on every load and every upsert, where the core keeps the order `hello` listed them in
 * — and the numbers a row prints beside them.
 */
object DeviceOrder {
    /** `localeCompare` on the names, keeping two equal names in the order they came. */
    fun byName(devices: List<Device>): List<Device> =
        SessionLayout.stableSorted(devices) { lhs, rhs -> NameOrder.compare(lhs.name, rhs.name) < 0 }

    /** The online devices, in name order: what the New session drawer offers. */
    fun online(devices: List<Device>): List<Device> = byName(devices).filter { it.online }

    /**
     * How many sessions each device has that nobody archived by hand, which is the count a device
     * row prints.
     */
    fun sessionCounts(sessions: List<Session>): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (session in sessions) if (!session.archived) counts[session.deviceID] = (counts[session.deviceID] ?: 0) + 1
        return counts
    }
}
