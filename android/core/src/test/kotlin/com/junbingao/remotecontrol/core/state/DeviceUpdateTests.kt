package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateResult
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.PairingProgress
import com.junbingao.remotecontrol.core.protocol.PairingStep
import com.junbingao.remotecontrol.core.transport.GatewayConfig
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The wire cases of RCCore's suite — `servedVersion`, `decoding`, `decodingWithoutUpdateFields`,
// `request` — are in `protocol/DeviceUpdateTests.kt`, and the demo device's own — `demoRetry` but for
// its last line, `demoRefusesTheSameBuild`, `demoRefusesOffline` — in `demo/DeviceUpdateTests.kt`.
// `demoRetry` here takes the same steps for the line that reads `DeviceUpdate.notice`.

/**
 * Amendment A36: what a device says about its client now that the gateway keeps every machine on the
 * wheel it serves, and when a person may ask for an update at all.
 */
class DeviceUpdateTests {
    private val served = "a".repeat(64)
    private val old = "b".repeat(64)

    private fun device(build: String?, online: Boolean = true, state: DeviceUpdateState = DeviceUpdateState.idle,
                       message: String? = null): Device =
        Device(deviceID = "d1", name = "mac-studio", platform = DevicePlatform.macos, hostname = "mac-studio.local",
               arch = "arm64", clientVersion = "0.1.0", clientBuild = build, updateState = state, updateMessage = message,
               online = online, lastSeen = 0, createdAt = 0)

    /** A device on the served build says nothing and offers nothing. */
    @Test
    fun current() {
        val device = device(build = served)
        assertNull(DeviceUpdate.notice(device))
        assertFalse(DeviceUpdate.canRetry(device))
    }

    /** A device on another build, or on none, is the gateway's to bring forward. */
    @Test
    fun behind() {
        assertNull(DeviceUpdate.notice(device(build = old)))
        assertNull(DeviceUpdate.notice(device(build = null)))
        assertFalse(DeviceUpdate.canRetry(device(build = old)))
    }

    /** An update in flight is said, and is not something to retry. */
    @Test
    fun updating() {
        val device = device(build = old, state = DeviceUpdateState.updating)
        assertEquals(DeviceUpdate.Notice.Updating, DeviceUpdate.notice(device))
        assertFalse(DeviceUpdate.canRetry(device))
    }

    /** A failed update shows the device's own reason, and only then offers a retry. */
    @Test
    fun failed() {
        val device = device(build = old, state = DeviceUpdateState.failed, message = "2 sessions are running")
        assertEquals(DeviceUpdate.Notice.Failed("2 sessions are running"), DeviceUpdate.notice(device))
        assertTrue(DeviceUpdate.canRetry(device))
        assertNull(DeviceUpdate.block(device, servedBuild = served))
    }

    /** A refusal the app holds is shown the same way as one the gateway kept. */
    @Test
    fun localRefusal() {
        val device = device(build = old, state = DeviceUpdateState.failed, message = "2 sessions are running")
        assertEquals(DeviceUpdate.Notice.Failed("already on this build"),
                     DeviceUpdate.notice(device, localError = "already on this build"))
    }

    /** An offline device, and a gateway with no wheel, cannot be retried. */
    @Test
    fun blocked() {
        val stranded = device(build = old, state = DeviceUpdateState.failed, message = "the device did not come back")
        assertEquals(DeviceUpdate.Block.noServedBuild, DeviceUpdate.block(stranded, servedBuild = null))
        assertNull(GatewayConfig.empty.servedBuild)
        assertEquals(DeviceUpdate.Block.offline, DeviceUpdate.block(device(build = old, online = false), servedBuild = served))
    }

    /** The demo's failed device takes a retry, then comes back on the new build, and its row says nothing. */
    @Test
    fun demoRetry() = runTest {
        val gateway = demoGateway()
        val laptop = DemoFixtures.laptopDeviceID
        val stranded = gateway.devices().firstOrNull { it.deviceID == laptop }
        assertEquals(DeviceUpdateState.failed, stranded?.updateState)

        val result = gateway.request(GatewayRequest.updateDevice(deviceID = laptop, build = DemoFixtures.servedBuild),
                                     DeviceUpdateResult.serializer())
        assertTrue(result.accepted)

        var settled: Device? = null
        var waited = 0.seconds
        while (waited < 15.seconds) {
            delay(100.milliseconds)
            waited += 100.milliseconds
            val device = gateway.devices().firstOrNull { it.deviceID == laptop }
            if (device?.updateState == DeviceUpdateState.idle) {
                settled = device
                break
            }
        }
        assertEquals(DemoFixtures.servedBuild, settled?.clientBuild)
        assertEquals(true, settled?.let { DeviceUpdate.notice(it) == null })
        gateway.disconnect()
    }
}

/** Amendment A23: the link a host prints as a QR code, and the claim it leads to. */
class PairingClaimTests {
    private val gateway = GatewayEndpoint("https://rc.example.com")

    /** The printed link carries a claim token for this gateway. */
    @Test
    fun parses() {
        val link = PairingClaimLink(payload = "https://rc.example.com/pair#7ZK3M9Q2X5H8B1V4N6P0R2T4W6", gateway = gateway)
        assertEquals("7ZK3M9Q2X5H8B1V4N6P0R2T4W6", link?.token)
    }

    /** A default port and a trailing slash still name the same gateway. */
    @Test
    fun canonicalOrigin() {
        assertEquals("ABCDEFGH", PairingClaimLink(payload = "https://RC.example.com:443/pair/#ABCDEFGH", gateway = gateway)?.token)
    }

    /** A link for another gateway is not ours to claim. */
    @Test
    fun otherGateway() {
        assertNull(PairingClaimLink(payload = "https://other.example.com/pair#ABCDEFGH", gateway = gateway))
    }

    /** Anything that is not a claim link is refused before the gateway hears about it. */
    @Test
    fun malformed() {
        for (payload in listOf("https://rc.example.com/pair", "https://rc.example.com/#ABCDEFGH", "not a url at all",
                               "https://rc.example.com/pair#short", "https://rc.example.com/pair#ABCDEFGI!")) {
            assertNull(PairingClaimLink(payload = payload, gateway = gateway), payload)
        }
    }

    /** Either origin the app knows the gateway by is accepted. */
    @Test
    fun severalOrigins() {
        val lan = GatewayEndpoint("http://192.168.1.20:8080")
        val link = PairingClaimLink(payload = "https://rc.example.com/pair#ABCDEFGH", gateways = listOf(lan, gateway))
        assertEquals("ABCDEFGH", link?.token)
    }

    /** A claimed token hands the flow the gateway's own pairing code. */
    @Test
    fun claimFollowsProgress() = runTest {
        val gateway = demoGateway()
        val flow = PairingFlow(api = gateway)
        flow.begin()
        val minted = flow.code
        assertTrue(minted.isNotEmpty())

        flow.claim(token = DemoFixtures.claimToken)
        val claimed = flow.code
        assertEquals(DemoFixtures.pairingClaim.code, claimed)
        assertNotEquals(minted, claimed)
        // The scan flow has no one-liner: the host ran one to get here.
        assertTrue(flow.command.isEmpty())

        flow.receive(AppFrame.PairingProgress(PairingProgress(code = claimed, step = PairingStep.enrolled, device = null)))
        assertEquals(PairingStep.enrolled, flow.reached)
        gateway.disconnect()
    }

    /** An unknown token is refused. */
    @Test
    fun unknownToken() = runTest {
        val gateway = demoGateway()
        val flow = PairingFlow(api = gateway)
        assertFailsWith<GatewayErrorBody> { flow.claim(token = "0000000000000000000000000A") }
        gateway.disconnect()
    }
}
