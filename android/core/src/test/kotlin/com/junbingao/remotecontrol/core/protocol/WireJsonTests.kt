package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.persistence.CachedWorkspace
import com.junbingao.remotecontrol.core.transport.PushRoute
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules `WireJson` reads and writes by, which RCCore spells field by field in its decoders:
 * what an absent or null field becomes, what is written and what is left out, and the two places
 * kotlinx reads differently from Foundation.
 */
class WireJsonTests {
    /** An absent field and a null one both take the value RCCore's `decodeIfPresent … ??` gives them. */
    @Test
    fun absentAndNullTakeTheDefault() {
        val absent = jsonObjectOf("device_id" to "d").decode<Device>()
        val nulls = jsonObjectOf("device_id" to "d", "name" to null, "platform" to null, "online" to null,
                                 "agents" to null, "update_state" to null).decode<Device>()
        for (device in listOf(absent, nulls)) {
            assertEquals("d", device.name, "the name falls back to the id")
            assertEquals(DevicePlatform.linux, device.platform)
            assertFalse(device.online)
            assertEquals(emptyList(), device.agents)
            assertEquals(DeviceUpdateState.idle, device.updateState)
        }
        // A field RCCore reads without `IfPresent` is still required, null or absent.
        assertTrue(runCatching { jsonObjectOf("name" to "n").decode<Device>() }.isFailure)
        assertTrue(runCatching { jsonObjectOf("device_id" to null).decode<Device>() }.isFailure)
    }

    /** What is written: every value, and an optional only when it holds one. */
    @Test
    fun writing() {
        val encoded = JSONValue.encode(StreamTextPayload(text = "t"))
        assertEquals(jsonObjectOf("text" to "t", "done" to false), encoded)
        assertNull(encoded["delta"], "an empty optional is left out, as `encodeIfPresent` leaves it")
    }

    /** Where RCCore's synthesized decoding requires a field its initializer defaults, so does this. */
    @Test
    fun requiredWhereRCCoreRequiresIt() {
        assertTrue(runCatching { jsonObjectOf("devices" to emptyList<Any>()).decode<CachedWorkspace>() }.isFailure)
        assertTrue(runCatching {
            jsonObjectOf("text" to "t", "model" to "m", "strength" to "strong")
                .decode<com.junbingao.remotecontrol.core.transport.PolishRequest>()
        }.isFailure, "a polish request names its context, if only as an empty list")
    }

    /** A push route with no version reads as none, while one built in code is this build's. */
    @Test
    fun pushRouteVersion() {
        assertEquals(0, jsonObjectOf("device_id" to "d", "session_id" to "s").decode<PushRoute>().version)
        assertEquals(1, PushRoute(kind = PushKind.error, deviceID = "d", sessionID = "s", deviceName = "", title = "").version)
    }

    /**
     * The two known differences from Foundation's decoder, pinned so a change in either is seen:
     * kotlinx takes a quoted number or boolean as its value where RCCore refuses the frame, and
     * refuses `5.0` for a whole-number field where RCCore takes it. Neither is something a gateway
     * or a device sends.
     */
    @Test
    fun knownDifferences() {
        assertEquals(7, jsonObjectOf("seq" to "7", "kind" to "notice").decode<SessionEvent>().seq)
        assertTrue(jsonObjectOf("agent" to "a", "available" to "true").decode<AgentInfo>().available)
        assertTrue(runCatching { jsonObjectOf("seq" to 7.0, "kind" to "notice").decode<SessionEvent>() }.isFailure)
        // The tree accessors read `5.0` as five, as RCCore's `intValue` does.
        assertEquals(5, JsonPrimitive(5.0).intValue)
        assertNull(JsonNull.intValue)
    }
}
