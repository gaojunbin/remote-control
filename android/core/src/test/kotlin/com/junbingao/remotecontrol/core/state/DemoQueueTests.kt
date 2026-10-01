package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration

// The rest of RCCore's suite of this name, the demo device's queue itself, is in
// `demo/DemoQueueTests.kt`; this is its one case that drives a `ChatStore`.

/**
 * Amendment A43 on the demo device: every queue in `ts` order with no two entries under one `ts`,
 * `queue_ts` honoured, `queue_remove` answering `not_found` for what it no longer holds, and an edit
 * going back to its place.
 */
class DemoQueueTests {
    /** An edited message goes back to its place in the demo's line. */
    @Test
    fun editRoundTrip() = runTest {
        val gateway = demoGateway(echoDelay = Duration.ZERO, resumeDelay = null, holdsQueue = true)
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.liveSessionID })
        val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
        chat.agent = DemoFixtures.claude
        backgroundScope.launch {
            gateway.events.collect { event -> if (event is GatewayEvent.Frame) chat.receive(event.frame) }
        }

        chat.open()
        assertEquals(listOf("demo-queued-suite", "demo-queued-regression", "demo-queued-evidence"),
                     chat.timeline.queue.map { it.id }, "the subscribe reply carries the line (A6)")
        assertEquals(2, chat.timeline.queue.lastOrNull()?.attachments)
        assertEquals(3, chat.session.queued)

        val middle = chat.timeline.queue[1]
        chat.draft = "a note of my own"
        chat.beginEdit(middle)
        settle { chat.timeline.queue.size == 2 }
        assertEquals(listOf("demo-queued-suite", "demo-queued-evidence"), chat.timeline.queue.map { it.id })
        assertEquals(middle.text, chat.draft)

        chat.draft = "Add a regression test for the refresh race, and one for logout."
        assertEquals(ChatStore.SendOutcome.accepted, chat.send())
        settle { chat.timeline.queue.size == 3 }
        assertEquals(listOf("Then run the full test suite.",
                            "Add a regression test for the refresh race, and one for logout.",
                            "Here are the CI log and a screenshot of the failing run."),
                     chat.timeline.queue.map { it.text })
        assertEquals(middle.ts, chat.timeline.queue[1].ts, "under the ts it left")
        assertEquals("a note of my own", chat.draft)

        val removing = chat.removeQueued(chat.timeline.queue[0].id)
        assertEquals(2, chat.timeline.queue.size, "a removed row leaves the list before the reply")
        assertEquals(2, chat.session.queued, "and the chip counts one fewer")
        removing.join()
        assertNull(chat.errorMessage, "the device let go of it")
        settle { chat.timeline.queue.firstOrNull()?.ts == middle.ts }
        assertEquals(2, chat.timeline.queue.map { it.id }.size, "and its snapshot agrees")
        assertEquals(middle.ts, chat.timeline.queue.firstOrNull()?.ts)
    }
}
