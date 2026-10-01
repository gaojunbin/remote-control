package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AgentLabel
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.agentLabel
import java.text.Collator
import java.util.Locale

/**
 * One machine and everything the session list shows under it: the sessions something still holds,
 * then the ones it is finished with. A device with neither is never built, so the list carries no
 * empty shelves.
 */
data class DeviceGroup(
    val device: Device,
    /** The reader folded this machine away. The rows are still here, so a count or a test can look inside without expanding anything. */
    val collapsed: Boolean,
    /** What a CLI or the device still holds, most urgent first. */
    val active: List<Session>,
    /** What nothing owns any more, plus what was archived by hand, newest first. */
    val archive: List<Session>,
    val archiveExpanded: Boolean,
) {
    val id: String get() = device.deviceID
    val name: String get() = device.name
    val online: Boolean get() = device.online
    val isEmpty: Boolean get() = active.isEmpty() && archive.isEmpty()
}

/**
 * Filtering, splitting and ordering for the session list: first by device, then by whether the
 * session is still live. It is a pure function of its arguments so the rule can be tested without a
 * view, a store or a connection, and so the web app can implement the same one.
 */
object SessionListLayout {
    /**
     * A session belongs under its device's Archive when nothing owns it any more
     * (`control == none`) or when it was archived by hand. Everything a CLI or the device still holds
     * stays above it.
     */
    fun isArchived(session: Session): Boolean = session.archived || session.control == SessionControl.none

    /**
     * Close is offered on exactly one kind of row: a session the device is driving —
     * `control == remote`, whoever created it — that is not archived. A row a terminal holds offers
     * none, because the terminal owns it and it leaves Active by itself the moment the terminal
     * exits; a row already in the Archive offers none either, because writing to it is what brings
     * it back (A15). Closing ends the session on the machine and only then files it (A39;
     * `docs/DESIGN.md` § "Close, then the Archive").
     */
    fun offersClose(session: Session): Boolean = session.control == SessionControl.remote && !session.archived

    /**
     * Title, working directory and agent, by id and by label. The device name is deliberately not
     * searched: it is a group header, and matching on it would empty every other group.
     */
    fun matches(session: Session, query: String): Boolean {
        if (query.isEmpty()) return true
        return session.title.lowercase().contains(query) ||
            session.cwd.lowercase().contains(query) ||
            session.agent.lowercase().contains(query) ||
            session.agentLabel.lowercase().contains(query)
    }

    /** Waiting on the user first, then working, then everything at rest. A `starting` agent counts as working; it is about to be. */
    fun urgency(state: SessionState): Int {
        if (state.isBlockedOnUser) return 0
        if (state == SessionState.running || state == SessionState.starting) return 1
        return 2
    }

    /**
     * The agent ids a list actually contains, in label order. The filter offers these and nothing
     * else, so it never names an agent nobody is running.
     */
    fun agents(sessions: List<Session>): List<String> {
        val labels = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        return sessions.map { it.agent }.filter { it.isNotEmpty() }.distinct()
            .sortedWith { left, right -> labels.compare(AgentLabel.name(left), AgentLabel.name(right)) }
    }

    /**
     * @param deviceFilter a device id, or null for every device.
     * @param agentFilter an agent id, or null for every agent. Applied before the grouping, so a
     *   machine whose sessions are all filtered out is gone.
     * @param collapsedDevices the device ids the reader folded away. A search unfolds every group it
     *   matched, without touching the preference.
     * @param archiveExpanded the device ids whose Archive the reader opened. A search opens any
     *   Archive with a match on top of these, and that opening is never persisted.
     */
    fun build(sessions: List<Session>, devices: List<Device>,
              deviceFilter: String? = null, agentFilter: String? = null,
              query: String = "",
              collapsedDevices: Set<String> = emptySet(),
              archiveExpanded: Set<String> = emptySet()): List<DeviceGroup> {
        val needle = query.trimmed.lowercase()
        val searching = needle.isNotEmpty()
        val matching = sessions.filter { session ->
            if (deviceFilter != null && session.deviceID != deviceFilter) return@filter false
            if (agentFilter != null && session.agent != agentFilter) return@filter false
            matches(session, query = needle)
        }

        val active = LinkedHashMap<String, MutableList<Session>>()
        val archive = LinkedHashMap<String, MutableList<Session>>()
        for (session in matching) {
            val shelf = if (isArchived(session)) archive else active
            shelf.getOrPut(session.deviceID) { mutableListOf() }.add(session)
        }

        val known = LinkedHashMap<String, Device>()
        for (device in devices) known.putIfAbsent(device.deviceID, device)
        val groups = (active.keys + archive.keys).map { deviceID ->
            val held = (active[deviceID] ?: emptyList<Session>()).sortedWith { left, right -> ordered(left, right) }
            val done = (archive[deviceID] ?: emptyList<Session>()).sortedByDescending { it.updatedAt }
            // A search that found something inside the Archive opens it: a result nobody can see is
            // not a result.
            val open = deviceID in archiveExpanded || (searching && done.isNotEmpty())
            // A group only exists here because something in it matched, so a search unfolds it too.
            // Clearing the query folds it back.
            DeviceGroup(device = known[deviceID] ?: unlisted(deviceID),
                        collapsed = !searching && deviceID in collapsedDevices,
                        active = held, archive = done, archiveExpanded = open)
        }

        return groups.sortedWith { left, right -> precedes(left, right) }
    }

    /**
     * Machines with something live come first, then the rest, each by their most recent activity. A
     * machine never jumps the page because its name sorts early, and never sinks because the gateway
     * listed it late.
     */
    private fun precedes(lhs: DeviceGroup, rhs: DeviceGroup): Int {
        if (lhs.active.isEmpty() != rhs.active.isEmpty()) return if (rhs.active.isEmpty()) -1 else 1
        val left = activity(lhs)
        val right = activity(rhs)
        if (left != right) return right.compareTo(left)
        if (lhs.name != rhs.name) return lhs.name.compareTo(rhs.name)
        return lhs.id.compareTo(rhs.id)
    }

    private fun activity(group: DeviceGroup): Long = (group.active + group.archive).maxOfOrNull { it.updatedAt } ?: 0

    private fun ordered(lhs: Session, rhs: Session): Int {
        val left = urgency(lhs.state)
        val right = urgency(rhs.state)
        if (left != right) return left.compareTo(right)
        return rhs.updatedAt.compareTo(lhs.updatedAt)
    }

    /** A session can name a device the gateway never listed. It still gets a group, under its own id, rather than disappearing from the list. */
    private fun unlisted(deviceID: String): Device =
        Device(deviceID = deviceID, name = deviceID, platform = DevicePlatform.linux, hostname = "", arch = "",
               clientVersion = "", online = false, lastSeen = 0, createdAt = 0)
}
