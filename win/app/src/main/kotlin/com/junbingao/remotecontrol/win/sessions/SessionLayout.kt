package com.junbingao.remotecontrol.win.sessions

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.state.DeviceGroup
import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.win.strings.S

/**
 * `web/src/stores/sessions.ts`: the one grouping rule every session list follows
 * (`docs/DESIGN.md` § "Session lists: by device, then by activity") — a group per device, its
 * active rows first, then that device's own Archive. The Sessions page and the chat sidebar both
 * read it, so the two lists never disagree.
 *
 * It hands back the core's `DeviceGroup`, but it is the web's selector rather than the core's
 * `SessionListLayout`, and the two part where the web decides: the search reads the title a row
 * prints ("Untitled session" included), the working directory and the device's name, and never
 * the agent; the agent filter offers the agents in id order; and a tie keeps the order the gateway
 * listed the sessions in, as the web's stable sorts do.
 */
object SessionLayout {
    /**
     * Active is what a CLI or the device still holds: `control` is `remote`, `terminal` or
     * `shared`. `control: "none"` means the CLI exited and nothing owns the session any more, so it
     * belongs to the Archive with the sessions the reader archived by hand.
     */
    fun isActive(session: Session): Boolean = !session.archived && session.control != SessionControl.none

    /** Attention first, then a running turn, then the rest. Lower sorts earlier. */
    fun activityRank(session: Session): Int {
        if (session.state.isBlockedOnUser) return 0
        if (session.state == SessionState.running || session.state == SessionState.starting) return 1
        return 2
    }

    /**
     * @param deviceFilter one device, or null for all of them.
     * @param agentFilter one agent, or null for all of them. Applied before the grouping, so a
     *   device whose sessions it removes disappears with them.
     * @param query free text over the title, the working directory and the device's name. A search
     *   opens every group it matched in and every Archive a match landed in, without touching the
     *   stored choices.
     * @param collapsedDevices the devices the reader folded shut.
     * @param archiveExpanded the devices whose Archive the reader opened.
     */
    fun build(
        sessions: List<Session>,
        devices: List<Device>,
        deviceFilter: String? = null,
        agentFilter: String? = null,
        query: String = "",
        collapsedDevices: Set<String> = emptySet(),
        archiveExpanded: Set<String> = emptySet(),
    ): List<DeviceGroup> {
        val known = LinkedHashMap<String, Device>()
        for (device in devices) known.putIfAbsent(device.deviceID, device)
        val needle = query.trimmed.lowercase()
        val visible = sessions.filter { session ->
            if (deviceFilter != null && session.deviceID != deviceFilter) return@filter false
            if (agentFilter != null && session.agent != agentFilter) return@filter false
            matches(session, needle, known[session.deviceID]?.name ?: "")
        }

        // The groups in the order their first session was listed, which is what a tie in the
        // ordering below falls back to.
        val order = mutableListOf<String>()
        val active = HashMap<String, MutableList<Session>>()
        val archive = HashMap<String, MutableList<Session>>()
        for (session in visible) {
            if (session.deviceID !in active && session.deviceID !in archive) order += session.deviceID
            val shelf = if (isActive(session)) active else archive
            shelf.getOrPut(session.deviceID) { mutableListOf() } += session
        }

        val searching = needle.isNotEmpty()
        val groups = order.map { deviceID ->
            val held = stableSorted(active[deviceID].orEmpty()) { lhs, rhs ->
                val left = activityRank(lhs)
                val right = activityRank(rhs)
                if (left != right) left < right else lhs.updatedAt > rhs.updatedAt
            }
            val done = stableSorted(archive[deviceID].orEmpty()) { lhs, rhs -> lhs.updatedAt > rhs.updatedAt }
            DeviceGroup(
                device = known[deviceID] ?: placeholder(deviceID),
                collapsed = !searching && deviceID in collapsedDevices,
                active = held,
                archive = done,
                archiveExpanded = deviceID in archiveExpanded || (searching && done.isNotEmpty()),
            )
        }

        // Devices with something live first, each half by its most recent activity.
        return stableSorted(groups) { lhs, rhs ->
            if (lhs.active.isEmpty() != rhs.active.isEmpty()) lhs.active.isNotEmpty() else lastActivity(lhs) > lastActivity(rhs)
        }
    }

    /** The agents present in the list, once each, in id order, for the agent filter. */
    fun agents(sessions: List<Session>): List<String> = sessions.map { it.agent }.toSet().sorted()

    /** Every session the reader has not archived, newest activity first. */
    fun unarchived(sessions: List<Session>): List<Session> =
        stableSorted(sessions.filter { !it.archived }) { lhs, rhs -> lhs.updatedAt > rhs.updatedAt }

    /** How many sessions are waiting on the person, for the sidebar's footer. */
    fun countWaiting(sessions: List<Session>): Int = sessions.count { it.state.isBlockedOnUser }

    private fun matches(session: Session, needle: String, deviceName: String): Boolean {
        if (needle.isEmpty()) return true
        return "${S.sessionTitle(session)} ${session.cwd} $deviceName".lowercase().contains(needle)
    }

    private fun lastActivity(group: DeviceGroup): Long = (group.active + group.archive).maxOfOrNull { it.updatedAt } ?: 0

    /**
     * A session on a device the gateway no longer lists still needs a group, so it gets one named
     * after its own id rather than disappearing.
     */
    private fun placeholder(deviceID: String): Device = Device(
        deviceID = deviceID, name = deviceID, platform = DevicePlatform.linux, hostname = deviceID, arch = "",
        clientVersion = "", online = false, lastSeen = 0, createdAt = 0,
    )

    /** `Array.prototype.sort`, which keeps equal elements in the order they came. */
    fun <Element> stableSorted(items: List<Element>, precedes: (Element, Element) -> Boolean): List<Element> =
        items.sortedWith { lhs, rhs ->
            when {
                precedes(lhs, rhs) -> -1
                precedes(rhs, lhs) -> 1
                else -> 0
            }
        }
}
