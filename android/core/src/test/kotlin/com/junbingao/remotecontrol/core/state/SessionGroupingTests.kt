package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Session list: one group per device. */
class SessionGroupingTests {
    private val mac = "device-mac"
    private val linux = "device-linux"

    private fun device(id: String, name: String, online: Boolean = true): Device =
        Device(deviceID = id, name = name, platform = DevicePlatform.macos, hostname = "$name.local", arch = "arm64",
               clientVersion = "0.1.0", online = online, lastSeen = 0, createdAt = 0)

    private fun session(id: String, device: String = "device-mac", title: String = "Work", cwd: String = "/src",
                        agent: String = "claude", state: SessionState = SessionState.idle,
                        control: SessionControl = SessionControl.remote, updatedAt: Long = 0,
                        archived: Boolean = false): Session =
        Session(sessionID = id, deviceID = device, agent = agent, title = title, cwd = cwd, state = state,
                control = control, updatedAt = updatedAt, archived = archived)

    private val devices: List<Device>
        get() = listOf(device(mac, name = "mac-studio"), device(linux, name = "ci-runner", online = false))

    /** A session splits into its own device's live rows or its own Archive. */
    @Test
    fun perDeviceSplit() {
        val sessions = listOf(
            session("live", updatedAt = 100),
            session("ended", control = SessionControl.none, updatedAt = 90),
            session("elsewhere", device = linux, updatedAt = 80),
        )
        val groups = SessionListLayout.build(sessions = sessions, devices = devices)
        assertEquals(listOf(mac, linux), groups.map { it.id })
        assertEquals(listOf("live"), groups.first().active.map { it.sessionID })
        assertEquals(listOf("ended"), groups.first().archive.map { it.sessionID })
        assertEquals(listOf("elsewhere"), groups.last().active.map { it.sessionID })
        assertTrue(groups.last().archive.isEmpty())
        assertEquals("mac-studio", groups.first().name)
        assertFalse(groups.last().online)
    }

    /** Anything a CLI or a device still holds stays out of the Archive. */
    @Test
    fun activeMembership() {
        for (control in listOf(SessionControl.remote, SessionControl.terminal, SessionControl.shared)) {
            assertFalse(SessionListLayout.isArchived(session("s", control = control)), "$control")
        }
        assertTrue(SessionListLayout.isArchived(session("s", control = SessionControl.none)))
    }

    /** Close is offered on a row the device drives, and on no other. */
    @Test
    fun closeIsOfferedOnOneKindOfRow() {
        assertTrue(SessionListLayout.offersClose(session("driven", control = SessionControl.remote)))
        // The terminal owns these; the row leaves Active when the CLI exits.
        for (control in listOf(SessionControl.terminal, SessionControl.shared, SessionControl.none)) {
            assertFalse(SessionListLayout.offersClose(session("held", control = control)), "$control")
        }
        // Nothing in the Archive offers anything: writing to it brings it back.
        assertFalse(SessionListLayout.offersClose(session("filed", control = SessionControl.remote, archived = true)))
        assertFalse(SessionListLayout.offersClose(session("filed", control = SessionControl.terminal, archived = true)))
    }

    /** A machine with live work leads, then the rest by their last activity. */
    @Test
    fun groupOrder() {
        val sessions = listOf(
            session("quiet", control = SessionControl.none, updatedAt = 900),
            session("busy", device = linux, state = SessionState.running, updatedAt = 10),
        )
        assertEquals(listOf(linux, mac), SessionListLayout.build(sessions = sessions, devices = devices).map { it.id })

        val both = listOf(
            session("older", updatedAt = 10),
            session("newer", device = linux, updatedAt = 500),
        )
        assertEquals(listOf(linux, mac), SessionListLayout.build(sessions = both, devices = devices).map { it.id })
    }

    /** A machine with nothing to show is not rendered at all. */
    @Test
    fun emptyDeviceIsDropped() {
        val groups = SessionListLayout.build(sessions = listOf(session("s")), devices = devices)
        assertEquals(listOf(mac), groups.map { it.id })
    }

    /** Inside a device, the user is asked first, then the work, then the rest. */
    @Test
    fun orderWithinDevice() {
        val sessions = listOf(
            session("idle", state = SessionState.idle, updatedAt = 900),
            session("running", state = SessionState.running, updatedAt = 100),
            session("approval", state = SessionState.needsApproval, updatedAt = 50),
            session("input", state = SessionState.needsInput, updatedAt = 80),
            session("starting", state = SessionState.starting, updatedAt = 200),
        )
        val held = SessionListLayout.build(sessions = sessions, devices = devices).firstOrNull()?.active
        assertEquals(listOf("input", "approval", "starting", "running", "idle"), held?.map { it.sessionID })
    }

    /** A hand-archived session joins its device's Archive, whatever still owns it. */
    @Test
    fun manuallyArchived() {
        val sessions = listOf(
            session("byHand", state = SessionState.running, updatedAt = 50, archived = true),
            session("ended", control = SessionControl.none, updatedAt = 99),
            session("live", updatedAt = 10),
        )
        val group = SessionListLayout.build(sessions = sessions, devices = devices).firstOrNull()
        assertEquals(listOf("live"), group?.active?.map { it.sessionID })
        assertEquals(listOf("ended", "byHand"), group?.archive?.map { it.sessionID })
        // The row itself says which of the two it is, so the list can mark it.
        assertEquals(true, group?.archive?.lastOrNull()?.archived)
        assertEquals(false, group?.archive?.firstOrNull()?.archived)
    }

    /** A device with nothing archived reports an empty Archive, so the header is hidden. */
    @Test
    fun emptyArchiveIsHidden() {
        val groups = SessionListLayout.build(sessions = listOf(session("live")), devices = devices)
        assertEquals(true, groups.firstOrNull()?.archive?.isEmpty())
    }

    /** A device with only archived sessions still gets its group. */
    @Test
    fun archiveOnlyDevice() {
        val groups = SessionListLayout.build(sessions = listOf(session("ended", control = SessionControl.none)),
                                             devices = devices)
        assertEquals(listOf(mac), groups.map { it.id })
        assertEquals(true, groups.firstOrNull()?.active?.isEmpty())
        assertEquals(1, groups.firstOrNull()?.archive?.size)
    }

    /** An Archive is closed until it is asked for, and a match inside opens it. */
    @Test
    fun archiveExpansion() {
        val sessions = listOf(session("ended", title = "Add traces", control = SessionControl.none))
        val closed = SessionListLayout.build(sessions = sessions, devices = devices)
        assertEquals(false, closed.firstOrNull()?.archiveExpanded)

        val asked = SessionListLayout.build(sessions = sessions, devices = devices, archiveExpanded = setOf(mac))
        assertEquals(true, asked.firstOrNull()?.archiveExpanded)

        val found = SessionListLayout.build(sessions = sessions, devices = devices, query = "traces")
        assertEquals(true, found.firstOrNull()?.archiveExpanded)

        // A search that matches only live rows leaves the Archive alone.
        val live = sessions + session("live", title = "Add traces to the gateway")
        val missed = SessionListLayout.build(sessions = live, devices = devices, query = "gateway")
        assertEquals(true, missed.firstOrNull()?.archive?.isEmpty())
        assertEquals(false, missed.firstOrNull()?.archiveExpanded)
    }

    /**
     * A session that comes back to life leaves the Archive at once, and folds back.
     *
     * Amendment A15: the device clears `archived` when the session comes back to life and publishes
     * it. Nothing about the row's place is remembered, so the next build of the list already has it
     * among the live rows, and the one after that folds it back if the reader archives it again.
     */
    @Test
    fun revivedSessionLeavesTheArchive() {
        val defaults = MemoryUserDefaults()
        val store = SessionStore(defaults = defaults)
        store.toggleArchive(mac)
        val dormant = session("resumed", state = SessionState.stopped, control = SessionControl.none, updatedAt = 40,
                              archived = true)
        val sessions = mutableListOf(session("live", state = SessionState.running, updatedAt = 60),
                                     session("ended", control = SessionControl.none, updatedAt = 50),
                                     dormant)

        val folded = store.groups(sessions, devices = devices)
        assertEquals(listOf("live"), folded.firstOrNull()?.active?.map { it.sessionID })
        assertEquals(listOf("ended", "resumed"), folded.firstOrNull()?.archive?.map { it.sessionID })

        // The session.updated a resumed session publishes: the flag is gone and a terminal owns it
        // again.
        sessions[2] = session("resumed", state = SessionState.running, control = SessionControl.terminal, updatedAt = 120)
        val revived = store.groups(sessions, devices = devices)
        assertEquals(listOf("resumed", "live"), revived.firstOrNull()?.active?.map { it.sessionID })
        assertEquals(listOf("ended"), revived.firstOrNull()?.archive?.map { it.sessionID })
        // The Archive is open because the reader opened it, not because the row left: the stored
        // choice is per device and survives the move.
        assertEquals(true, revived.firstOrNull()?.archiveExpanded)
        assertEquals(setOf(mac), store.expandedArchives)

        sessions[2] = dormant
        val refolded = store.groups(sessions, devices = devices)
        assertEquals(listOf("live"), refolded.firstOrNull()?.active?.map { it.sessionID })
        assertEquals(listOf("ended", "resumed"), refolded.firstOrNull()?.archive?.map { it.sessionID })
    }

    /**
     * Clearing archived alone moves the row, and the last one out takes the header.
     *
     * The flag on its own decides the half, with nothing else changing. A session its device still
     * owns sits in the Archive only while `archived` is set, so clearing it is enough to move the
     * row.
     */
    @Test
    fun archivedFlagAloneDecidesTheHalf() {
        val held = session("resumed", control = SessionControl.remote, updatedAt = 40)
        val archivedByHand = held.copy(archived = true)

        assertTrue(SessionListLayout.isArchived(archivedByHand))
        assertFalse(SessionListLayout.isArchived(held))

        val before = SessionListLayout.build(sessions = listOf(archivedByHand), devices = devices)
        assertEquals(listOf("resumed"), before.firstOrNull()?.archive?.map { it.sessionID })
        assertEquals(true, before.firstOrNull()?.active?.isEmpty())

        // The whole Archive was that one row, so the sub-header goes with it.
        val after = SessionListLayout.build(sessions = listOf(held), devices = devices)
        assertEquals(true, after.firstOrNull()?.archive?.isEmpty())
        assertEquals(listOf("resumed"), after.firstOrNull()?.active?.map { it.sessionID })
    }

    /** Search reads the title, the folder and the agent, and drops empty machines. */
    @Test
    fun search() {
        val sessions = listOf(
            session("a", title = "Fix the parser", cwd = "/src/gateway", agent = "claude"),
            session("b", device = linux, title = "Traces", cwd = "/work/api", agent = "codex"),
        )
        fun rows(query: String): List<String> =
            SessionListLayout.build(sessions = sessions, devices = devices, query = query).flatMap { group ->
                group.active.map { it.sessionID }
            }
        assertEquals(listOf("a"), rows("parser"))
        assertEquals(listOf("b"), rows("/work"))
        assertEquals(listOf("b"), rows("codex"))
        assertEquals(listOf("a"), rows("claude code"))
        assertEquals(listOf("a"), rows("  PARSER "))
        assertTrue(rows("nothing here").isEmpty())
        assertEquals(1, SessionListLayout.build(sessions = sessions, devices = devices, query = "parser").size)
    }

    /** The agent filter applies before the grouping, so a machine can disappear. */
    @Test
    fun agentFilter() {
        val sessions = listOf(
            session("claude-one", agent = "claude", updatedAt = 100),
            session("codex-one", agent = "codex", updatedAt = 90),
            session("claude-two", device = linux, agent = "claude", updatedAt = 50),
        )
        assertEquals(listOf("claude", "codex"), SessionListLayout.agents(sessions))

        val codex = SessionListLayout.build(sessions = sessions, devices = devices, agentFilter = "codex")
        assertEquals(listOf(mac), codex.map { it.id })
        assertEquals(listOf("codex-one"), codex.firstOrNull()?.active?.map { it.sessionID })

        val claude = SessionListLayout.build(sessions = sessions, devices = devices, agentFilter = "claude")
        assertEquals(listOf(mac, linux), claude.map { it.id })
    }

    /** The agent filter offers only the agents the list contains, in label order. */
    @Test
    fun agentOptions() {
        assertTrue(SessionListLayout.agents(emptyList()).isEmpty())
        assertEquals(listOf("codex"), SessionListLayout.agents(listOf(session("a", agent = "codex"))))
        val mixed = listOf(session("a", agent = "codex"), session("b", agent = "amp"), session("c", agent = "claude"))
        assertEquals(listOf("amp", "claude", "codex"), SessionListLayout.agents(mixed))
    }

    /** With one device picked, only that group is built. */
    @Test
    fun deviceFilter() {
        val sessions = listOf(session("a"), session("b", device = linux))
        val only = SessionListLayout.build(sessions = sessions, devices = devices, deviceFilter = linux)
        assertEquals(listOf(linux), only.map { it.id })
        assertEquals(listOf("b"), only.firstOrNull()?.active?.map { it.sessionID })
    }

    /** A folded machine keeps its rows, so a count still reads them. */
    @Test
    fun collapsedGroup() {
        val groups = SessionListLayout.build(sessions = listOf(session("s")), devices = devices, collapsedDevices = setOf(mac))
        assertEquals(true, groups.firstOrNull()?.collapsed)
        assertEquals(1, groups.firstOrNull()?.active?.size)
    }

    /** A search unfolds the machines it matched, and clearing it folds them back. */
    @Test
    fun searchUnfoldsCollapsedGroups() {
        val sessions = listOf(session("s", title = "Fix the parser"))
        val found = SessionListLayout.build(sessions = sessions, devices = devices, query = "parser",
                                            collapsedDevices = setOf(mac))
        assertEquals(false, found.firstOrNull()?.collapsed)

        val cleared = SessionListLayout.build(sessions = sessions, devices = devices, collapsedDevices = setOf(mac))
        assertEquals(true, cleared.firstOrNull()?.collapsed)
    }

    /** A session on a device the gateway never listed is still shown. */
    @Test
    fun unknownDevice() {
        val groups = SessionListLayout.build(sessions = listOf(session("s", device = "ghost")), devices = devices)
        assertEquals(listOf("ghost"), groups.map { it.id })
        assertEquals("ghost", groups.firstOrNull()?.name)
        assertEquals(false, groups.firstOrNull()?.online)
    }

    /** Folding a machine away and opening its Archive both outlive the launch. */
    @Test
    fun collapseRoundTrip() {
        val defaults = MemoryUserDefaults()
        val store = SessionStore(defaults = defaults)
        assertTrue(store.collapsedDevices.isEmpty())
        assertTrue(store.expandedArchives.isEmpty())

        store.toggleCollapsed(mac)
        store.toggleArchive(linux)
        assertEquals(listOf(mac), defaults.stringArray(forKey = "sessions.collapsedDevices"))
        assertEquals(listOf(linux), defaults.stringArray(forKey = "sessions.archiveExpanded"))

        val reloaded = SessionStore(defaults = defaults)
        assertEquals(setOf(mac), reloaded.collapsedDevices)
        assertEquals(setOf(linux), reloaded.expandedArchives)
        assertTrue(reloaded.isCollapsed(mac))

        reloaded.toggleCollapsed(mac)
        assertTrue(SessionStore(defaults = defaults).collapsedDevices.isEmpty())
    }

    /** The agent filter is a view of the list, not a setting. */
    @Test
    fun agentFilterIsNotPersisted() {
        val defaults = MemoryUserDefaults()
        val store = SessionStore(defaults = defaults)
        assertNull(store.agentFilter)
        store.agentFilter = "codex"
        assertNull(SessionStore(defaults = defaults).agentFilter)
    }
}
