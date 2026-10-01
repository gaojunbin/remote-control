package com.junbingao.remotecontrol.win.sessions

import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentOption
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import com.junbingao.remotecontrol.core.protocol.DirectoryEntry
import com.junbingao.remotecontrol.core.protocol.DirectoryListing
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GitStatus
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionResult
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import com.junbingao.remotecontrol.core.protocol.boolValue
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.devices.ListsFakeChannel
import com.junbingao.remotecontrol.win.sessions.drawer.DirectoryBrowser
import com.junbingao.remotecontrol.win.sessions.drawer.DirectoryProbe
import com.junbingao.remotecontrol.win.sessions.drawer.NewSessionForm
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Mac's "Lists: new session drawer" — `web/tests/NewSessionDrawer.test.tsx` and
 * `DirectoryPicker.test.tsx`, on the form and the picker behind the drawer: the defaults it starts
 * from, what a device or agent change clears, what `session.create` carries, and what making a
 * folder says and does (A37).
 */
class ListsDrawerTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    private val claude = AgentInfo(
        agent = "claude", available = true,
        models = listOf(AgentOption(id = "sonnet", label = "Sonnet 4.5"), AgentOption(id = "opus", label = "Opus")),
        defaultModel = "sonnet",
        permissionModes = listOf(AgentOption(id = "default", label = "Ask")), defaultPermissionMode = "default",
        efforts = listOf(AgentOption(id = "high", label = "High")), defaultEffort = "high",
        capabilities = listOf(AgentCapability.worktree),
    )
    private val codex = AgentInfo(agent = "codex", available = true, speeds = listOf(AgentOption(id = "priority", label = "Fast")))
    private val grok = AgentInfo(agent = "grok", available = false)

    private fun device(id: String, agents: List<AgentInfo>) =
        Device(deviceID = id, name = id, platform = DevicePlatform.macos, hostname = id, arch = "arm64", clientVersion = "",
               online = true, lastSeen = 0, createdAt = 0, agents = agents)

    private fun created(session: Session) = Result.success(JSONValue.encode(SessionResult(session = session)))

    @Test
    fun startsOnThePresetDeviceWhenItIsOnlineElseTheFirst() {
        val devices = listOf(device("a", listOf(claude)), device("b", listOf(codex)))
        assertEquals("b", NewSessionForm(devices = devices, preset = "b").deviceID)
        assertEquals("a", NewSessionForm(devices = devices, preset = "gone").deviceID)
        assertEquals("a", NewSessionForm(devices = devices, preset = null).deviceID)
        assertNull(NewSessionForm(devices = emptyList(), preset = null).deviceID)
    }

    @Test
    fun picksTheChosenAgentElseTheFirstInstalledElseTheFirst() {
        val form = NewSessionForm(devices = emptyList(), preset = null)
        val mixed = device("a", listOf(grok, codex, claude))
        assertEquals("codex", form.agent(of = mixed)?.agent)
        form.chooseAgent("claude")
        assertEquals("claude", form.agent(of = mixed)?.agent)
        assertEquals("grok", form.agent(of = device("b", listOf(grok)))?.agent)
        assertNull(form.agent(of = null))
    }

    @Test
    fun startsAtTheAgentsOwnDefaultsAndClearsTheChoicesWithTheAgent() {
        val form = NewSessionForm(devices = listOf(device("a", listOf(claude))), preset = null)
        assertEquals("sonnet", form.model(of = claude))
        assertEquals("high", form.effort(of = claude))
        assertEquals("default", form.permissionMode(of = claude))
        form.options = form.options.copy(model = "opus")
        assertEquals("opus", form.model(of = claude))
        form.chooseAgent("codex")
        assertEquals("sonnet", form.model(of = claude))
    }

    @Test
    fun aDeviceChangeKeepsATypedPathAndDropsAnInheritedOne() {
        val form = NewSessionForm(devices = listOf(device("a", listOf(claude)), device("b", listOf(claude))), preset = null)
        form.cwd = "/Users/me/dev"
        form.chooseDevice("b")
        assertTrue(form.cwd.isEmpty())
        form.setPath("/Users/me/work")
        form.chooseDevice("a")
        assertEquals("/Users/me/work", form.cwd)
    }

    @Test
    fun startsOnlyOnAnInstalledAgentAndAPath() {
        val form = NewSessionForm(devices = emptyList(), preset = null)
        val mac = device("a", listOf(claude))
        assertFalse(form.canStart(device = mac, agent = claude))
        form.setPath("  ")
        assertFalse(form.canStart(device = mac, agent = claude))
        form.setPath("/work")
        assertTrue(form.canStart(device = mac, agent = claude))
        assertFalse(form.canStart(device = mac, agent = grok))
        assertFalse(form.canStart(device = null, agent = claude))
    }

    @Test
    fun sendsTheDefaultsTheSpeedTierAndAWorktreeOnlyWhenAsked() = runTest {
        val mac = device("a", listOf(claude, codex))
        val reply = Session(sessionID = "s1", deviceID = "a", agent = "claude", title = "", cwd = "/work")
        val channel = ListsFakeChannel(mapOf("session.create" to created(reply)))
        val form = NewSessionForm(devices = listOf(mac), preset = null)
        form.setPath(" /work ")
        form.worktree = true
        assertEquals("s1", form.start(device = mac, agent = claude, channel = channel)?.sessionID)
        val first = channel.asked.first().body
        assertTrue(first["cwd"]?.stringValue == "/work" && first["model"]?.stringValue == "sonnet")
        assertTrue(first["effort"]?.stringValue == "high" && first["permission_mode"]?.stringValue == "default")
        assertTrue(first["worktree"]?.boolValue == true && first["speed"] == null)

        form.chooseAgent("codex")
        form.worktree = true
        form.options = form.options.copy(speed = SpeedChange.Tier("priority"))
        form.start(device = mac, agent = codex, channel = channel)
        val second = channel.asked.last().body
        assertTrue(second["speed"]?.stringValue == "priority" && second["worktree"] == null)
        assertNull(second["model"])

        form.options = form.options.copy(speed = SpeedChange.Standard)
        form.start(device = mac, agent = codex, channel = channel)
        assertNull(channel.asked.last().body["speed"])
    }

    @Test
    fun saysTheDevicesOwnSentenceWhenItRefuses() = runTest {
        val mac = device("a", listOf(claude))
        val form = NewSessionForm(devices = listOf(mac), preset = null)
        form.setPath("/work")
        val refusing = ListsFakeChannel(mapOf(
            "session.create" to Result.failure(GatewayErrorBody(code = GatewayErrorCode.agentUnavailable, message = "claude is not installed")),
        ))
        assertNull(form.start(device = mac, agent = claude, channel = refusing))
        assertEquals("claude is not installed", form.error)
        assertNull(NewSessionForm.refusal(GatewayErrorBody(code = GatewayErrorCode.timeout, message = "timeout")))
        assertEquals("Could not start the session.", NewSessionForm.refusal(TransportError.NotConnected))
    }

    @Test
    fun makesTheFolderStandsInItAndPicksIt() = runTest {
        val home = DirectoryListing(path = "/Users/me/dev", parent = "/Users/me",
                                    entries = listOf(DirectoryEntry(name = "api", path = "/Users/me/dev/api", isGit = true)), recent = emptyList())
        val made = DirectoryListing(path = "/Users/me/dev/notes", parent = "/Users/me/dev", entries = emptyList(), recent = emptyList())
        val channel = ListsFakeChannel(mapOf("device.dirs" to Result.success(JSONValue.encode(home)),
                                             "device.mkdir" to Result.success(JSONValue.encode(made))))
        val browser = DirectoryBrowser(deviceID = "a", channel = channel)
        browser.open(null)
        browser.startNaming()
        browser.folderName = " notes "
        browser.createFolder()
        assertEquals("/Users/me/dev/notes", browser.listing?.path)
        assertTrue(!browser.naming && browser.folderName.isEmpty() && browser.folderError == null)
        val mkdir = channel.asked.last()
        assertTrue(mkdir.type == "device.mkdir" && mkdir.body["path"]?.stringValue == "/Users/me/dev" && mkdir.body["name"]?.stringValue == "notes")
    }

    @Test
    fun saysAClashInItsOwnWordsAndKeepsTheName() = runTest {
        val home = DirectoryListing(path = "/Users/me/dev", parent = null, entries = emptyList(), recent = emptyList())
        val channel = ListsFakeChannel(mapOf(
            "device.dirs" to Result.success(JSONValue.encode(home)),
            "device.mkdir" to Result.failure(GatewayErrorBody(code = GatewayErrorCode.conflict, message = "/Users/me/dev/api already exists")),
        ))
        val browser = DirectoryBrowser(deviceID = "a", channel = channel)
        browser.open(null)
        browser.startNaming()
        browser.folderName = "api"
        browser.createFolder()
        assertEquals("A folder with that name already exists.", browser.folderError)
        assertTrue(browser.naming && browser.folderName == "api")
    }

    @Test
    fun showsWhatTheDeviceSaidAboutANameItRefused() {
        assertEquals(
            "a folder name cannot begin with a dot",
            DirectoryBrowser.folderRefusal(GatewayErrorBody(code = GatewayErrorCode.badRequest, message = "a folder name cannot begin with a dot")),
        )
        assertEquals("Not found.", DirectoryBrowser.folderRefusal(GatewayErrorBody(code = GatewayErrorCode.notFound, message = "not_found")))
    }

    @Test
    fun willNotSendAnEmptyName() = runTest {
        val home = DirectoryListing(path = "/Users/me/dev", parent = null, entries = emptyList(), recent = emptyList())
        val channel = ListsFakeChannel(mapOf("device.dirs" to Result.success(JSONValue.encode(home))))
        val browser = DirectoryBrowser(deviceID = "a", channel = channel)
        browser.open(null)
        browser.startNaming()
        browser.folderName = "   "
        browser.createFolder()
        assertEquals(listOf("device.dirs"), channel.asked.map { it.type })
    }

    @Test
    fun leavesTheRowBehindWhenTheListingChanges() = runTest {
        val home = DirectoryListing(path = "/Users/me/dev", parent = "/Users/me", entries = emptyList(), recent = emptyList())
        val channel = ListsFakeChannel(mapOf("device.dirs" to Result.success(JSONValue.encode(home))))
        val browser = DirectoryBrowser(deviceID = "a", channel = channel)
        browser.open(null)
        browser.startNaming()
        browser.open("/Users/me")
        assertFalse(browser.naming)
    }

    @Test
    fun probesNothingUntilThereIsAPathAndReadsAnOlderAnswerAsChecking() = runTest {
        val probe = DirectoryProbe()
        assertEquals(DirectoryProbe.Status.idle, probe.status(deviceID = null, path = "/work").status)
        assertEquals(DirectoryProbe.Status.idle, probe.status(deviceID = "a", path = "  ").status)
        assertEquals(DirectoryProbe.Status.checking, probe.status(deviceID = "a", path = "/work").status)
        val listing = DirectoryListing(path = "/work", parent = "/", entries = emptyList(), recent = emptyList())
        val channel = ListsFakeChannel(mapOf("device.dirs" to Result.success(JSONValue.encode(listing)),
                                             "device.git" to Result.success(JSONValue.encode(GitStatus(isRepo = true, branch = "main")))))
        probe.run(deviceID = "a", path = "/work", channel = channel)
        assertEquals(DirectoryProbe.Status.exists, probe.status(deviceID = "a", path = "/work").status)
        assertEquals("main", probe.status(deviceID = "a", path = "/work").git?.branch)
        assertEquals(DirectoryProbe.Status.checking, probe.status(deviceID = "a", path = "/work/api").status)
        probe.run(deviceID = "a", path = "/nope", channel = ListsFakeChannel(emptyMap()))
        assertEquals(DirectoryProbe.Status.missing, probe.status(deviceID = "a", path = "/nope").status)
    }
}
