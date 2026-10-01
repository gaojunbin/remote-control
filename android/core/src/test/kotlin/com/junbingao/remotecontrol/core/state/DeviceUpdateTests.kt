package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState
import com.junbingao.remotecontrol.core.transport.GatewayConfig
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The wire cases of RCCore's suite — `servedVersion`, `decoding`, `decodingWithoutUpdateFields`,
// `request` — are in `protocol/DeviceUpdateTests.kt`. The cases that drive the demo gateway —
// `demoRetry`, `demoRefusesTheSameBuild`, `demoRefusesOffline`, and the A23 suite's
// `claimFollowsProgress` and `unknownToken` — arrive with the demo.

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
}
