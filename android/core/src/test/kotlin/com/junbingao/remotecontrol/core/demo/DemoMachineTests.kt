@file:OptIn(ExperimentalCoroutinesApi::class)

package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AgentsResult
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.DirectoryListing
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.TerminalAttachResult
import com.junbingao.remotecontrol.core.protocol.TerminalOpenResult
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf
import com.junbingao.remotecontrol.core.state.request
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** What the demo device answers about its machines: their shells, folders, agents and names. */
class DemoMachineTests {
    private val mac = DemoFixtures.macDeviceID

    /**
     * Amendment A38: a shell greets once its id is known, answers a line with the line, follows a
     * resize, replays its scrollback on attach, and ends on `exit` with a code.
     */
    @Test
    fun shellEchoesAndExits() = runTest {
        val gateway = demoGateway()
        val log = EventLog(backgroundScope, gateway)
        val terminal = gateway.request(GatewayRequest.terminalOpen(deviceID = mac, cols = 100, rows = 30),
                                       TerminalOpenResult.serializer()).terminalID
        runCurrent()
        assertTrue(log.frames.none { it is AppFrame.TerminalOutput }, "the prompt follows the reply")
        advanceTimeBy(120.milliseconds)
        runCurrent()
        fun output() = log.frames.filterIsInstance<AppFrame.TerminalOutput>().map { it.output }
        assertEquals(1, output().single().seq)
        assertEquals("Remote Control demo shell — nothing here reaches a real machine.\r\ndemo:~$ ",
                     output().single().bytes?.decodeToString())

        gateway.request(GatewayRequest.terminalInput(deviceID = mac, terminalID = terminal, data = "pwd\r".encodeToByteArray()))
        gateway.request(GatewayRequest.terminalResize(deviceID = mac, terminalID = terminal, cols = 120, rows = 40))
        gateway.request(GatewayRequest.terminalInput(deviceID = mac, terminalID = terminal, data = "size\r".encodeToByteArray()))
        runCurrent()
        assertEquals(listOf(1, 2, 3), output().map { it.seq })
        assertEquals("pwd\r\n/Users/me\r\ndemo:~$ ", output()[1].bytes?.decodeToString())
        assertEquals("size\r\n120x40\r\ndemo:~$ ", output()[2].bytes?.decodeToString())
        val attached = gateway.request(GatewayRequest.terminalAttach(deviceID = mac, terminalID = terminal),
                                       TerminalAttachResult.serializer())
        assertEquals(120 to 40, attached.cols to attached.rows)
        assertEquals(output().joinToString("") { it.bytes!!.decodeToString() }, attached.scrollbackBytes?.decodeToString())

        gateway.request(GatewayRequest.terminalInput(deviceID = mac, terminalID = terminal, data = "exit\r".encodeToByteArray()))
        runCurrent()
        val exited = log.frames.filterIsInstance<AppFrame.TerminalExited>().single().exited
        assertEquals(terminal, exited.terminalID)
        assertEquals(0, exited.code)
        assertEquals("That terminal is gone.", assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.terminalAttach(deviceID = mac, terminalID = terminal))
        }.message)
        gateway.request(GatewayRequest.terminalClose(deviceID = mac, terminalID = terminal))
    }

    /** Protocol 7.3: four shells per machine, none where the machine offers none or is not there. */
    @Test
    fun aMachineRunsFourShells() = runTest {
        val gateway = demoGateway()
        val opened = (1..4).map {
            gateway.request(GatewayRequest.terminalOpen(deviceID = mac, cols = 80, rows = 24), TerminalOpenResult.serializer())
        }
        assertEquals(4, opened.map { it.terminalID }.toSet().size)
        assertEquals("This device already runs four terminals.", assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.terminalOpen(deviceID = mac, cols = 80, rows = 24))
        }.message)
        gateway.request(GatewayRequest.terminalClose(deviceID = mac, terminalID = opened.first().terminalID))
        gateway.request(GatewayRequest.terminalOpen(deviceID = mac, cols = 80, rows = 24))

        assertEquals(GatewayErrorCode.unsupported, assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.terminalOpen(deviceID = DemoFixtures.laptopDeviceID, cols = 80, rows = 24))
        }.code)
        assertEquals(GatewayErrorCode.deviceOffline, assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.terminalOpen(deviceID = DemoFixtures.ciDeviceID, cols = 80, rows = 24))
        }.code)
        assertEquals("data must be base64", assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest(type = "terminal.input", body = jsonObjectOf(
                "device_id" to mac, "terminal_id" to opened[1].terminalID, "data" to "not base64!")))
        }.message)
    }

    /**
     * Amendment A37: the picker lists the demo's tree, a folder made there is listed under its
     * parent, and the device's name rules are said the device's way.
     */
    @Test
    fun directoriesListAndMake() = runTest {
        val gateway = demoGateway()
        val home = gateway.request(GatewayRequest.dirs(deviceID = mac), DirectoryListing.serializer())
        assertEquals("/Users/me/dev", home.path)
        assertEquals("/Users/me", home.parent)
        assertEquals(listOf("gateway", "notes", "remote-control"), home.entries.map { it.name })
        assertEquals(listOf(true, false, true), home.entries.map { it.isGit })
        assertEquals(2, home.recent.size)

        val made = gateway.request(GatewayRequest.mkdir(deviceID = mac, path = "/Users/me/dev", name = "scratch"),
                                   DirectoryListing.serializer())
        assertEquals("/Users/me/dev/scratch", made.path)
        assertEquals("/Users/me/dev", made.parent)
        assertTrue(made.entries.isEmpty())
        assertEquals(listOf("gateway", "notes", "remote-control", "scratch"),
                     gateway.request(GatewayRequest.dirs(deviceID = mac), DirectoryListing.serializer()).entries.map { it.name })
        assertEquals(GatewayErrorCode.conflict, assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.mkdir(deviceID = mac, path = "/Users/me/dev", name = "scratch"))
        }.code)
        assertEquals("A folder name cannot start with a dot.", assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.mkdir(deviceID = mac, path = "/Users/me/dev", name = ".hidden"))
        }.message)
        assertEquals("No such directory: /nope", assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.dirs(deviceID = mac, path = "/nope"))
        }.message)
    }

    /**
     * Amendment A33: a machine's agents arrive with their quota after the moment it takes to read
     * them, and an offline machine is refused before that.
     */
    @Test
    fun agentsArriveWithTheirQuota() = runTest {
        val gateway = demoGateway()
        val start = currentTime
        val agents = gateway.request(GatewayRequest.agents(deviceID = mac), AgentsResult.serializer()).agents
        assertEquals(DemoGateway.defaultAgentsDelay.inWholeMilliseconds, currentTime - start)
        assertEquals(DemoFixtures.agentsWithQuota(deviceID = mac)?.map { it.agent }, agents.map { it.agent })
        assertEquals(3, agents.first().accounts?.first()?.limits?.size)
        assertNotNull(agents.first().accounts?.first()?.limitsCheckedAt)
        assertEquals(GatewayErrorCode.deviceOffline, assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.agents(deviceID = DemoFixtures.ciDeviceID))
        }.code)
        assertNull(gateway.devices().first().agents.first().accounts?.first()?.limits, "the stored list never carries a window")
    }

    /** A machine is renamed in place and published; a revoked one is gone with its sessions. */
    @Test
    fun renameAndRevoke() = runTest {
        val gateway = demoGateway()
        val log = EventLog(backgroundScope, gateway)
        assertEquals("studio", gateway.renameDevice(mac, name = "studio").name)
        assertEquals(GatewayErrorCode.notFound, assertFailsWith<GatewayErrorBody> {
            gateway.renameDevice("nope", name = "x")
        }.code)
        gateway.revokeDevice(DemoFixtures.ciDeviceID)
        runCurrent()
        assertEquals("studio", log.frames.filterIsInstance<AppFrame.DeviceUpdated>().single().device.name)
        assertEquals(DemoFixtures.ciDeviceID, log.frames.filterIsInstance<AppFrame.DeviceRemoved>().single().deviceID)
        assertTrue(gateway.devices().none { it.deviceID == DemoFixtures.ciDeviceID })
        assertTrue(gateway.sessions().none { it.deviceID == DemoFixtures.ciDeviceID })
    }
}
