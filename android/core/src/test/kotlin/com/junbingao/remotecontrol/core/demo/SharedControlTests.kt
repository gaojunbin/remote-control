package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Amendment A10 on the demo device: its attached session and the terminal one it cannot attach.
 * The wire cases of RCCore's suite of this name are `protocol.SharedControlTests`; the composer's
 * and the injection through `ChatStore` are `core-state`'s.
 */
class SharedControlTests {
    /** The demo carries an attached session and a terminal one that cannot attach. */
    @Test
    fun demoFixtures() {
        val sessions = DemoFixtures.sessions
        val shared = assertNotNull(sessions.firstOrNull { it.sessionID == DemoFixtures.sharedSessionID },
                                   "the demo is missing an A10 session")
        val hinted = assertNotNull(sessions.firstOrNull { it.sessionID == DemoFixtures.attachHintSessionID },
                                   "the demo is missing an A10 session")
        assertEquals(SessionControl.shared, shared.control)
        assertEquals(SessionState.idle, shared.state)
        assertEquals(SessionControl.terminal, hinted.control)

        val devices = DemoFixtures.devices
        assertEquals(true, devices.firstOrNull { it.deviceID == shared.deviceID }?.agent("claude")?.attachReady)
        assertEquals(false, devices.firstOrNull { it.deviceID == hinted.deviceID }?.agent("claude")?.attachReady)
    }
}
