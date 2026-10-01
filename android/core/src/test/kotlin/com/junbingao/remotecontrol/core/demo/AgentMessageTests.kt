package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.EventSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Amendment A30 on the demo device: words another agent put into its attached Claude
 * conversation. The suite's wire cases are `state.AgentMessageTests` (in `PolishTests.kt`, as in
 * RCCore).
 */
class AgentMessageTests {
    /** The demo's attached Claude session carries one such message. */
    @Test
    fun demo() {
        val rows = DemoFixtures.sharedHistory().mapNotNull { it.userMessage }.filter { it.source == EventSource.agent }
        assertEquals(1, rows.size)
        assertTrue(rows.first().text.startsWith("recon-ios:"))
    }
}
