package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.core.protocol.SessionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Amendment A11 on the demo device: the shared Codex thread it carries. The wire cases of RCCore's
 * suite of this name are `protocol.CodexDaemonTests`; the cases that drive `ChatStore` through the
 * demo are `core-state`'s.
 */
class CodexDaemonTests {
    /** The demo carries a shared Codex thread the app drives in full. */
    @Test
    fun demoFixtures() {
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.codexSharedSessionID },
                                    "the demo is missing the shared Codex thread")
        assertEquals("codex", session.agent)
        assertEquals(SessionControl.shared, session.control)
        assertEquals(EventSource.terminal, session.origin)
        assertTrue(session.state.isWorking)

        val agent = DemoFixtures.devices.firstOrNull { it.deviceID == session.deviceID }?.agent("codex")
        assertEquals(AgentAttach.daemon, agent?.attach)
        assertEquals(true, agent?.attachReady)
        assertEquals(true, agent?.sharedInterrupt)
        assertEquals(true, agent?.sharedSettings)
        assertEquals(true, agent?.sharedAttachments)

        val approval = DemoFixtures.codexSharedHistory().mapNotNull { it.approval }.lastOrNull()
        assertEquals(4, approval?.options?.size)
        assertEquals(RequestStatus.pending, approval?.status)
    }
}
