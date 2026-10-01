package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Amendment A20 on the demo device: the attached session's questions. The wire cases of RCCore's
 * suite of this name are `protocol.QuestionAnswerTests`; the composer's (`QuestionDraft`,
 * `ChatStore`) are `core-state`'s.
 */
class QuestionAnswerTests {
    /** The demo's attached session carries a question the terminal answered. */
    @Test
    fun demoCarriesATerminalAnswer() {
        val answered = DemoFixtures.sharedHistory().mapNotNull { it.question }.firstOrNull { it.by != null }
        assertEquals(EventSource.terminal, answered?.by)
        assertEquals(RequestStatus.resolved, answered?.status)
        assertEquals(RequestStatus.pending, DemoFixtures.sharedQuestion.status)
        assertEquals(true, DemoFixtures.sharedQuestion.questions.firstOrNull()?.allowText,
                     "so the composer's draft has somewhere to go")
    }
}
