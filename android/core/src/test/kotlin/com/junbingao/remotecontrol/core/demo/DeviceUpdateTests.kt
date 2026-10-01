@file:OptIn(ExperimentalCoroutinesApi::class)

package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.DeviceUpdateResult
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.state.request
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amendment A36 on the demo device: the machine the gateway could not bring forward takes a retry
 * and comes back on the served build. The wire cases of RCCore's suite of this name are
 * `protocol.DeviceUpdateTests`. RCCore's `demoRetry` also checks that the settled row says nothing
 * (`DeviceUpdate.notice`), and its pairing suite drives `PairingFlow`; both are `core-state`'s.
 */
class DeviceUpdateTests {
    /** The demo's failed device takes a retry, then comes back on the new build. */
    @Test
    fun demoRetry() = runTest {
        val gateway = demoGateway()
        val laptop = DemoFixtures.laptopDeviceID
        val stranded = gateway.devices().firstOrNull { it.deviceID == laptop }
        assertEquals(DeviceUpdateState.failed, stranded?.updateState)
        assertEquals(DemoFixtures.updateFailure, stranded?.updateMessage)

        val result = gateway.request(GatewayRequest.updateDevice(deviceID = laptop, build = DemoFixtures.servedBuild),
                                     DeviceUpdateResult.serializer())
        assertTrue(result.accepted)
        assertEquals(DemoFixtures.outdatedBuild, result.from)

        val before = gateway.devices().firstOrNull { it.deviceID == laptop }
        assertEquals(DeviceUpdateState.updating, before?.updateState)
        assertNull(before?.updateMessage)

        // RCCore polls for the device to come back; on the test's clock its restart is run through.
        advanceUntilIdle()
        val settled = gateway.devices().firstOrNull { it.deviceID == laptop }
        assertEquals(DeviceUpdateState.idle, settled?.updateState)
        assertEquals(DemoFixtures.servedBuild, settled?.clientBuild)
        assertEquals(DemoFixtures.servedClientVersion, settled?.clientVersion)
        gateway.disconnect()
    }

    /** A device already on the build refuses rather than reinstalling it. */
    @Test
    fun demoRefusesTheSameBuild() = runTest {
        val gateway = demoGateway()
        assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.updateDevice(deviceID = DemoFixtures.macDeviceID, build = DemoFixtures.servedBuild))
        }
        gateway.disconnect()
    }

    /** An offline device refuses before anything is downloaded. */
    @Test
    fun demoRefusesOffline() = runTest {
        val gateway = demoGateway()
        assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.updateDevice(deviceID = DemoFixtures.ciDeviceID, build = DemoFixtures.servedBuild))
        }
        gateway.disconnect()
    }
}
