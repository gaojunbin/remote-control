package com.junbingao.remotecontrol.win.devices

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import com.junbingao.remotecontrol.core.protocol.HelloFrame
import com.junbingao.remotecontrol.core.protocol.PairingProgress
import com.junbingao.remotecontrol.core.protocol.PairingStep
import com.junbingao.remotecontrol.core.protocol.STTConfig
import com.junbingao.remotecontrol.core.protocol.UserIdentity
import com.junbingao.remotecontrol.win.devices.adddevice.AddDevicePairing
import com.junbingao.remotecontrol.win.devices.adddevice.GatewayClock
import com.junbingao.remotecontrol.win.devices.adddevice.PairingChecklist
import com.junbingao.remotecontrol.win.devices.adddevice.PairingChecklist.Mark
import com.junbingao.remotecontrol.win.shared.Format
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Mac's "Lists: pairing" — `web/tests/AddDeviceModal.test.tsx`, on what the modal decides: the
 * steps the handshake lights, the progress line, the frames it listens to, when Continue is
 * enabled, which code a closing modal gives back, and the expiry.
 */
class ListsPairingTests {
    private fun pairing(): AddDevicePairing = AddDevicePairing().also { runBlocking { it.request(api = DemoGateway()) } }

    @Test
    fun lightsTheStepsAsTheHandshakeGoes() {
        assertEquals(listOf(Mark.done, Mark.active, Mark.idle), PairingChecklist.marks(null))
        assertEquals(listOf(Mark.done, Mark.active, Mark.idle), PairingChecklist.marks(PairingStep.waiting))
        assertEquals(listOf(Mark.done, Mark.done, Mark.idle), PairingChecklist.marks(PairingStep.enrolled))
        assertEquals(listOf(Mark.done, Mark.done, Mark.active), PairingChecklist.marks(PairingStep.online))
        assertEquals(listOf(Mark.done, Mark.done, Mark.done), PairingChecklist.marks(PairingStep.agents))
    }

    @Test
    fun fillsTheProgressLineAQuarterAStepFrom12PerCent() {
        assertEquals(12.0, PairingChecklist.progress(null))
        assertEquals(25.0, PairingChecklist.progress(PairingStep.waiting))
        assertEquals(50.0, PairingChecklist.progress(PairingStep.enrolled))
        assertEquals(100.0, PairingChecklist.progress(PairingStep.agents))
    }

    @Test
    fun namesTheAgentsTheNewDeviceFoundByID() {
        val device = Device(
            deviceID = "d", name = "new-laptop", platform = DevicePlatform.macos, hostname = "", arch = "", clientVersion = "",
            online = true, lastSeen = 0, createdAt = 0,
            agents = listOf(AgentInfo(agent = "claude", available = true), AgentInfo(agent = "grok", available = false),
                            AgentInfo(agent = "codex", available = true)),
        )
        assertEquals("claude · codex", PairingChecklist.agents(PairingProgress(code = "x", step = PairingStep.agents, device = device)))
        assertEquals("", PairingChecklist.agents(null))
    }

    @Test
    fun showsTheOneCommandTheGatewayHandedOut() {
        val pairing = pairing()
        assertEquals(DemoFixtures.pairingGrant.code, pairing.grant?.code)
        assertEquals(DemoFixtures.pairingGrant.install.macos, pairing.command)
        assertFalse(pairing.failed)
    }

    @Test
    fun saysSoWhenNoCodeCouldBeMade() {
        val pairing = AddDevicePairing()
        runBlocking { pairing.request(api = null) }
        assertTrue(pairing.failed && pairing.grant == null)
    }

    @Test
    fun walksTheLiveStepsAndOnlyThenEnablesContinue() {
        val pairing = pairing()
        val code = pairing.grant?.code ?: ""
        pairing.receive(AppFrame.PairingProgress(PairingProgress(code = code, step = PairingStep.enrolled)))
        assertTrue(pairing.step == PairingStep.enrolled && !pairing.connected)
        assertEquals(code, pairing.unclaimedCode)
        pairing.receive(AppFrame.PairingProgress(PairingProgress(code = code, step = PairingStep.online)))
        assertTrue(pairing.connected)
        assertNull(pairing.unclaimedCode)
    }

    @Test
    fun ignoresProgressFramesForADifferentCode() {
        val pairing = pairing()
        pairing.receive(AppFrame.PairingProgress(PairingProgress(code = "RC-OTHER-CODE", step = PairingStep.agents)))
        assertTrue(pairing.live == null && pairing.step == null && !pairing.connected)
    }

    @Test
    fun countsDownOnTheGatewaysClockAndCallsAnUnclaimedCodeExpired() {
        val pairing = pairing()
        val expires = pairing.grant?.expiresAt ?: 0
        assertEquals(90_000L, pairing.remaining(now = expires - 90_000))
        assertFalse(pairing.hasExpired(now = expires - 1))
        assertTrue(pairing.hasExpired(now = expires + 5))
        pairing.receive(AppFrame.PairingProgress(PairingProgress(code = pairing.grant?.code ?: "", step = PairingStep.online)))
        assertFalse(pairing.hasExpired(now = expires + 5))
    }

    @Test
    fun readsTheGatewaysClockOffHello() {
        val clock = GatewayClock()
        assertEquals(0L, clock.skew)
        clock.receive(AppFrame.Hello(hello(serverTime = Format.nowMillis + 60_000)))
        assertTrue(abs(clock.skew - 60_000) < 1_000)
        clock.receive(AppFrame.Hello(hello(serverTime = 0)))
        assertEquals(0L, clock.skew)
    }

    private fun hello(serverTime: Long) = HelloFrame(
        protocolVersion = 1, gatewayVersion = "1", user = UserIdentity(username = "admin"),
        devices = emptyList(), sessions = emptyList(), stt = STTConfig.disabled, serverTime = serverTime,
    )
}
