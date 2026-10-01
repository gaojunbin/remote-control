package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.protocol.EventSource
import kotlin.test.Test

/**
 * `ios/Verification/PolishChecks.swift`, the demo's line of it; the rest of that check group is
 * `state.PolishChecks`.
 */
class PolishChecks {
    /** A30, words another agent put in the conversation. */
    @Test
    fun agentMessages() {
        val checks = CheckRunner("polish")
        val agentRows = DemoFixtures.sharedHistory().filter { it.userMessage?.source == EventSource.agent }
        checks.equal(agentRows.size, 1, "the demo carries one message another agent filed")
        checks.assertAll()
    }
}
