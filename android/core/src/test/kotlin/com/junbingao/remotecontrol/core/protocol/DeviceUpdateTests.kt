package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.core.transport.GatewayConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Amendment A36, a device keeps itself current: the config, the device record and the request.
 * RCCore's suite of this name also holds what a row says and offers (`DeviceUpdate`) and the demo's
 * update; those cases, and the A23 pairing suite in the same file (`PairingClaimLink`), are in
 * `state/DeviceUpdateTests.kt` and `demo/DeviceUpdateTests.kt`.
 */
class DeviceUpdateTests {
    private val served = "a".repeat(64)
    private val old = "b".repeat(64)

    /** The config names the version a retry would install, or nothing at all. */
    @Test
    fun servedVersion() {
        // The gateway's own version and the client's differ here on purpose: `servedVersion` is the
        // wheel's, and a decoder that read the outer one would pass on a config where the two agree.
        val json = """
            {"public_origin": "https://rc.example.com", "version": "2.0.0",
             "client": {"version": "${AppBuild.shipped}", "build": "$served",
                        "url": "/dist/rc_client-latest.whl"}}
        """.trimIndent()
        val config = JSONValue.parse(json.encodeToByteArray()).decode<GatewayConfig>()
        assertEquals(AppBuild.shipped, config.servedVersion)
        assertNull(GatewayConfig.empty.servedVersion)

        // An older gateway answers with a build and no version; the screens that name it have
        // wording for that.
        val older = """
            {"public_origin": "https://rc.example.com", "version": "1.3.0",
             "client": {"build": "$served", "url": "/dist/rc_client-latest.whl"}}
        """.trimIndent()
        val decoded = JSONValue.parse(older.encodeToByteArray()).decode<GatewayConfig>()
        assertEquals(served, decoded.servedBuild)
        assertNull(decoded.servedVersion)
    }

    /** A device record carries the build, the state and the reason. */
    @Test
    fun decoding() {
        val json = """
            {"device_id": "d1", "name": "mac", "platform": "macos", "hostname": "mac.local",
             "arch": "arm64", "client_version": "0.1.0", "client_build": "$old",
             "update_state": "failed", "update_message": "the device did not come back",
             "online": true, "last_seen": 1, "created_at": 1, "latency_ms": null, "agents": []}
        """.trimIndent()
        val device = JSONValue.parse(json.encodeToByteArray()).decode<Device>()
        assertEquals(old, device.clientBuild)
        assertEquals(DeviceUpdateState.failed, device.updateState)
        assertEquals("the device did not come back", device.updateMessage)
    }

    /** A record with no update fields at all is a device at rest. */
    @Test
    fun decodingWithoutUpdateFields() {
        val json = """
            {"device_id": "d1", "name": "mac", "platform": "macos", "hostname": "mac.local",
             "arch": "arm64", "client_version": "0.1.0", "online": true,
             "last_seen": 1, "created_at": 1, "agents": []}
        """.trimIndent()
        val device = JSONValue.parse(json.encodeToByteArray()).decode<Device>()
        assertNull(device.clientBuild)
        assertEquals(DeviceUpdateState.idle, device.updateState)
    }

    /** The request names the device and the build it must land on. */
    @Test
    fun request() {
        val request = GatewayRequest.updateDevice(deviceID = "d1", build = served)
        assertEquals("device.update", request.type)
        assertEquals(JsonPrimitive("d1"), request.body["device_id"])
        assertEquals(JsonPrimitive(served), request.body["build"])
    }
}
