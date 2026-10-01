package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Amendment A28 on the demo device: the Grok session the leader shares with a terminal. The wire
 * cases of RCCore's suite of this name are `protocol.GrokLeaderTests`; the composer's cases and
 * the send through `ChatStore` are `core-state`'s.
 */
class GrokLeaderTests {
    /** The demo carries a Grok session the leader shares with a terminal. */
    @Test
    fun demoSharedSession() {
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.grokSharedSessionID })
        assertEquals(SessionControl.shared, session.control)
        assertEquals(EventSource.terminal, session.origin)
        assertTrue(session.state.isWorking)

        val agent = assertNotNull(DemoFixtures.devices.firstOrNull { it.deviceID == session.deviceID }?.agent("grok"))
        assertEquals(AgentAttach.leader, agent.attach)
        assertTrue(agent.attachReady)
        assertTrue(agent.sharedInterrupt && agent.sharedSettings)
        assertFalse(agent.sharedAttachments)

        val history = DemoFixtures.history(sessionID = session.sessionID)
        assertEquals(EventSource.terminal, history.firstOrNull()?.userMessage?.source)
        assertTrue(history.any { it.toolCall?.status == ToolStatus.running })
        // The turn is still running, which is what makes Stop worth drawing.
        assertTrue(history.all { it.kind != SessionEvent.turnCompletedKind })
    }
}
