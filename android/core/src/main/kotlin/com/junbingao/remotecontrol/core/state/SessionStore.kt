package com.junbingao.remotecontrol.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.Session

/**
 * Filtering, grouping and the list state that outlives a launch. It owns no network state; the
 * sessions themselves live in [ConnectionStore] and are corrected by every `hello`. The rule that
 * builds the groups is [SessionListLayout], a pure function this class only feeds.
 *
 * RCCore's `RelativeTime`, which shares its Swift file, is in `RelativeTime.kt`.
 */
class SessionStore(private val defaults: UserDefaults) {
    private object Key {
        const val collapsedDevices = "sessions.collapsedDevices"
        const val archiveExpanded = "sessions.archiveExpanded"
    }

    var searchText: String by mutableStateOf("")

    /**
     * An agent id, or null for every agent. A view of the list rather than a setting, so it starts at
     * All on every launch and is never written down.
     */
    var agentFilter: String? by mutableStateOf(null)

    /** The machines the reader folded away, by device id. */
    var collapsedDevices: Set<String> by mutableStateOf(defaults.stringArray(forKey = Key.collapsedDevices).orEmpty().toSet())
        private set

    /** The machines whose Archive the reader opened, by device id. */
    var expandedArchives: Set<String> by mutableStateOf(defaults.stringArray(forKey = Key.archiveExpanded).orEmpty().toSet())
        private set

    fun groups(sessions: List<Session>, devices: List<Device>): List<DeviceGroup> =
        SessionListLayout.build(sessions = sessions, devices = devices, agentFilter = agentFilter, query = searchText,
                                collapsedDevices = collapsedDevices, archiveExpanded = expandedArchives)

    /** The agents the filter offers, read from the whole list rather than from the filtered one, so choosing Codex never hides Claude Code. */
    fun agentOptions(sessions: List<Session>): List<String> = SessionListLayout.agents(sessions)

    fun isCollapsed(deviceID: String): Boolean = deviceID in collapsedDevices

    fun toggleCollapsed(deviceID: String) {
        collapsedDevices = toggled(collapsedDevices, deviceID)
        defaults.set(collapsedDevices.sorted(), forKey = Key.collapsedDevices)
    }

    fun toggleArchive(deviceID: String) {
        expandedArchives = toggled(expandedArchives, deviceID)
        defaults.set(expandedArchives.sorted(), forKey = Key.archiveExpanded)
    }

    /**
     * Put every machine and every Archive back the way a fresh install draws them. What the reader
     * folded away outlives a launch, so a run that asks for a clean slate has to say so about this
     * too, or it inherits the shape of the list somebody else left behind.
     */
    fun forgetListState() {
        collapsedDevices = emptySet()
        expandedArchives = emptySet()
        defaults.removeObject(forKey = Key.collapsedDevices)
        defaults.removeObject(forKey = Key.archiveExpanded)
    }

    private fun toggled(set: Set<String>, deviceID: String): Set<String> = if (deviceID in set) set - deviceID else set + deviceID
}
