package com.junbingao.remotecontrol.win.sessions

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.state.DeviceGroup
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Mac's "Lists: session layout" — `web/tests/sessionLayout.test.ts`: the grouping rule every
 * session list follows — a group per device, its active rows, then that device's own Archive —
 * tested on the selector both lists read.
 */
class ListsSessionLayoutTests {
    companion object {
        fun session(
            id: String, device: String = "dev-a", agent: String = "claude", state: SessionState = SessionState.idle,
            control: SessionControl = SessionControl.remote, archived: Boolean = false, minutesAgo: Long = 0,
            title: String? = null, cwd: String = "/work/api",
        ) = Session(sessionID = id, deviceID = device, agent = agent, title = title ?: id, cwd = cwd, state = state,
                    control = control, updatedAt = -minutesAgo * 60_000, archived = archived)

        fun device(id: String, name: String? = null, online: Boolean = true) =
            Device(deviceID = id, name = name ?: id, platform = DevicePlatform.linux, hostname = name ?: id, arch = "x86_64",
                   clientVersion = "0.1.0", online = online, lastSeen = 0, createdAt = 0)
    }

    private val devices = listOf(device("dev-a", name = "mac-studio"), device("dev-b", name = "ci-runner", online = false))

    private fun ids(sessions: List<Session>) = sessions.map { it.sessionID }

    private fun group(groups: List<DeviceGroup>, deviceID: String): DeviceGroup = assertNotNull(groups.firstOrNull { it.id == deviceID })

    @Test
    fun splitsADeviceIntoTheRowsACLIStillHoldsAndItsOwnArchive() {
        val sessions = listOf(session("remote", control = SessionControl.remote), session("terminal", control = SessionControl.terminal),
                              session("shared", control = SessionControl.shared),
                              session("gone", state = SessionState.stopped, control = SessionControl.none))
        val dev = group(SessionLayout.build(sessions, devices), "dev-a")
        assertEquals(listOf("remote", "shared", "terminal"), ids(dev.active).sorted())
        assertEquals(listOf("gone"), ids(dev.archive))
    }

    @Test
    fun filesAManuallyArchivedSessionUnderItsOwnDeviceWhateverHoldsIt() {
        val sessions = listOf(session("live"), session("filed", archived = true),
                              session("filed-b", device = "dev-b", control = SessionControl.shared, archived = true))
        val groups = SessionLayout.build(sessions, devices)
        assertEquals(listOf("live"), ids(group(groups, "dev-a").active))
        assertEquals(listOf("filed"), ids(group(groups, "dev-a").archive))
        assertEquals(emptyList(), ids(group(groups, "dev-b").active))
        assertEquals(listOf("filed-b"), ids(group(groups, "dev-b").archive))
    }

    @Test
    fun rendersNoGroupForADeviceWithNothingToShow() {
        val groups = SessionLayout.build(listOf(session("only")), devices)
        assertEquals(listOf("dev-a"), groups.map { it.id })
        assertTrue(groups.first().archive.isEmpty())
    }

    @Test
    fun putsDevicesWithSomethingLiveFirstThenBothHalvesByLastActivity() {
        val sessions = listOf(session("quiet-new", device = "dev-b", control = SessionControl.none, minutesAgo = 1),
                              session("live-old", device = "dev-a", minutesAgo = 90),
                              session("live-new", device = "dev-c", minutesAgo = 5),
                              session("quiet-old", device = "dev-d", control = SessionControl.none, minutesAgo = 300))
        val all = devices + listOf(device("dev-c"), device("dev-d"))
        assertEquals(listOf("dev-c", "dev-a", "dev-b", "dev-d"), SessionLayout.build(sessions, all).map { it.id })
    }

    @Test
    fun ordersADeviceByAttentionThenARunningTurnThenLastActivity() {
        val sessions = listOf(session("idle-new", minutesAgo = 1),
                              session("running-old", state = SessionState.running, minutesAgo = 30),
                              session("starting-new", state = SessionState.starting, minutesAgo = 2),
                              session("approval-old", state = SessionState.needsApproval, minutesAgo = 90),
                              session("input-new", state = SessionState.needsInput, minutesAgo = 5),
                              session("stopped-new", state = SessionState.stopped, minutesAgo = 0))
        val dev = group(SessionLayout.build(sessions, devices), "dev-a")
        assertEquals(listOf("input-new", "approval-old", "starting-new", "running-old", "stopped-new", "idle-new"), ids(dev.active))
    }

    @Test
    fun ordersOneDevicesArchiveByLastActivity() {
        val sessions = listOf(session("old", control = SessionControl.none, minutesAgo = 120),
                              session("new", archived = true, minutesAgo = 3),
                              session("mid", control = SessionControl.none, minutesAgo = 40))
        assertEquals(listOf("new", "mid", "old"), ids(group(SessionLayout.build(sessions, devices), "dev-a").archive))
    }

    @Test
    fun dropsAWholeGroupWhenTheAgentFilterEmptiesIt() {
        val sessions = listOf(session("a-claude", agent = "claude"), session("a-codex", agent = "codex"),
                              session("b-codex", device = "dev-b", agent = "codex"))
        val claude = SessionLayout.build(sessions, devices, agentFilter = "claude")
        assertEquals(listOf("dev-a"), claude.map { it.id })
        assertEquals(listOf("a-claude"), ids(claude.first().active))
        val codex = SessionLayout.build(sessions, devices, agentFilter = "codex")
        assertEquals(listOf("dev-a", "dev-b"), codex.map { it.id }.sorted())
    }

    @Test
    fun restrictsTheListToOneDevice() {
        val sessions = listOf(session("a-live"), session("a-gone", control = SessionControl.none), session("b-live", device = "dev-b"))
        val groups = SessionLayout.build(sessions, devices, deviceFilter = "dev-a")
        assertEquals(listOf("dev-a"), groups.map { it.id })
        assertEquals(listOf("a-live"), ids(groups.first().active))
        assertEquals(listOf("a-gone"), ids(groups.first().archive))
    }

    @Test
    fun readsTheCollapseAndArchiveStateOffTheGivenDeviceIDs() {
        val sessions = listOf(session("a-live"), session("a-gone", control = SessionControl.none),
                              session("b-live", device = "dev-b"), session("b-gone", device = "dev-b", control = SessionControl.none))
        val groups = SessionLayout.build(sessions, devices, collapsedDevices = setOf("dev-b"), archiveExpanded = setOf("dev-a"))
        val a = group(groups, "dev-a")
        val b = group(groups, "dev-b")
        assertTrue(!a.collapsed && a.archiveExpanded)
        assertTrue(b.collapsed && !b.archiveExpanded)
    }

    @Test
    fun opensAnArchiveASearchReachedIntoWithoutTouchingTheStoredIDs() {
        val sessions = listOf(session("live", title = "Fix the ingest regression"),
                              session("gone", control = SessionControl.none, title = "Ingest docs rewrite"))
        val matched = group(SessionLayout.build(sessions, devices, query = "docs"), "dev-a")
        assertTrue(ids(matched.active).isEmpty() && ids(matched.archive) == listOf("gone") && matched.archiveExpanded)
        val missed = group(SessionLayout.build(sessions, devices, query = "regression"), "dev-a")
        assertTrue(ids(missed.archive).isEmpty() && !missed.archiveExpanded)
    }

    @Test
    fun opensAFoldedDeviceASearchMatchedWithoutTouchingTheStoredIDs() {
        val sessions = listOf(session("live", title = "Fix the ingest regression"),
                              session("gone", control = SessionControl.none, title = "Ingest docs rewrite"))
        val folded = setOf("dev-a")
        assertTrue(group(SessionLayout.build(sessions, devices, collapsedDevices = folded), "dev-a").collapsed)
        val searched = group(SessionLayout.build(sessions, devices, query = "regression", collapsedDevices = folded), "dev-a")
        assertTrue(!searched.collapsed && ids(searched.active) == listOf("live"))
        val inArchive = group(SessionLayout.build(sessions, devices, query = "docs", collapsedDevices = folded), "dev-a")
        assertTrue(!inArchive.collapsed && inArchive.archiveExpanded)
    }

    @Test
    fun searchesTheTitleTheWorkingDirectoryAndTheDeviceName() {
        val sessions = listOf(session("one", title = "Fix flaky auth test", cwd = "/work/gateway"),
                              session("two", title = "Add traces", cwd = "/work/ingest"),
                              session("three", device = "dev-b", title = "Nightly sweep", cwd = "/work/api"))
        assertEquals(listOf("one"), ids(group(SessionLayout.build(sessions, devices, query = "FLAKY"), "dev-a").active))
        assertEquals(listOf("two"), ids(group(SessionLayout.build(sessions, devices, query = "ingest"), "dev-a").active))
        assertEquals(listOf("dev-b"), SessionLayout.build(sessions, devices, query = "ci-runner").map { it.id })
        // The web never searches the agent, which the core's own rule does.
        assertTrue(SessionLayout.build(sessions, devices, query = "claude").isEmpty())
    }

    @Test
    fun givesASessionOnADeviceTheGatewayNeverListedAGroupOfItsOwn() {
        val groups = SessionLayout.build(listOf(session("orphan", device = "dev-gone")), devices)
        assertEquals(listOf("dev-gone"), groups.map { it.name })
        assertFalse(groups.first().online)
    }

    @Test
    fun listsTheAgentsTheSessionsRunOnceEachInIDOrder() {
        val sessions = listOf(session("one", agent = "codex"), session("two", agent = "claude"),
                              session("three", agent = "codex"), session("four", agent = "grok"))
        assertEquals(listOf("claude", "codex", "grok"), SessionLayout.agents(sessions))
    }

    @Test
    fun countsTheUnarchivedSessionsWaitingOnTheReader() {
        val sessions = listOf(session("a", state = SessionState.needsApproval), session("b", state = SessionState.needsInput),
                              session("c", state = SessionState.needsInput, archived = true), session("d", state = SessionState.running))
        assertEquals(2, SessionLayout.countWaiting(SessionLayout.unarchived(sessions)))
    }

    @Test
    fun keepsTheOrderTheGatewayListedWhenTwoRowsTie() {
        val sessions = listOf(session("first", minutesAgo = 5), session("second", minutesAgo = 5), session("third", minutesAgo = 5))
        assertEquals(listOf("first", "second", "third"), ids(group(SessionLayout.build(sessions, devices), "dev-a").active))
    }
}

/**
 * The Mac's "Lists: untitled sessions" — `web/tests/SessionsPage.test.tsx` § "a session with no
 * title": the row's own words are what the search reads.
 */
class ListsUntitledTests {
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    @Test
    fun isFoundByASearchForTheWordsItPrints() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
        val untitled = ListsSessionLayoutTests.session("blank", title = " ")
        val devices = listOf(ListsSessionLayoutTests.device("dev-a"))
        assertEquals("Untitled session", S.sessionTitle(untitled))
        assertEquals(1, SessionLayout.build(listOf(untitled), devices, query = "untitled").size)
        InterfaceLanguageSource.current = InterfaceLanguage.zhHans
        assertEquals(1, SessionLayout.build(listOf(untitled), devices, query = "未命名").size)
    }
}
