package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.QueuePayload
import com.junbingao.remotecontrol.core.protocol.QueuedMessage
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.jsonOf
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The wire cases of RCCore's suite of this name (`attachmentsDecode`, `queueTsIsOptional`) are in
// `protocol/QueuedEditTests.kt`.

/**
 * Amendment A43: a queued message is edited by taking it out of the line with
 * `session.queue_remove`, editing its words in the composer, and sending them back with
 * `mode: "queue"` under the `ts` the entry had. These cover the store's half of it;
 * `docs/DESIGN.md` § "The composer" → **Up next** is the ruling.
 */
class QueuedEditTests {
    private val queue = listOf(
        QueuedMessage(id = "q-a", text = "Then run the full test suite.", ts = 1_000),
        QueuedMessage(id = "q-b", text = "Add a regression test.", ts = 2_000),
        QueuedMessage(id = "q-c", text = "Here are the screenshots.", ts = 3_000, attachments = 2),
    )

    private val steering = AgentInfo(agent = "codex", available = true,
                                     capabilities = listOf(AgentCapability.interrupt, AgentCapability.queue,
                                                           AgentCapability.steer, AgentCapability.commands))

    private fun store(tasks: CoroutineScope, state: SessionState = SessionState.running,
                      control: SessionControl = SessionControl.remote,
                      agent: AgentInfo = DemoFixtures.claude): Pair<ChatStore, QueueChannel> {
        val session = Session(sessionID = "s", deviceID = "d", agent = agent.agent, title = "T", cwd = "/tmp",
                              state = state, control = control)
        val channel = QueueChannel()
        val chat = ChatStore(session = session, channel = channel, tasks = tasks)
        chat.agent = agent
        chat.receive(AppFrame.SessionEvent(sessionID = "s", deviceID = "d",
                                           event = SessionEvent(seq = 1, ts = 1, kind = SessionEvent.queueKind,
                                                                body = SessionEventBody.Queue(QueuePayload(pending = queue)))))
        return chat to channel
    }

    // Taking it out

    /** Remove takes the row out of the list before the device answers. */
    @Test
    fun removeLeavesTheListAtOnce() = runTest {
        val (chat, channel) = store(backgroundScope)
        val removing = chat.removeQueued("q-b")
        assertEquals(listOf("q-a", "q-c"), chat.timeline.queue.map { it.id }, "a swipe has already taken the row off the screen")
        assertEquals(2, chat.session.queued, "and the chip counts one fewer")
        removing.join()
        assertEquals(listOf("session.queue_remove"), channel.requests.map { it.type })
        assertEquals("q-b", channel.requests.firstOrNull()?.body?.get("queued_id")?.stringValue)
    }

    /** A Remove that finds the message gone says so the way an edit does. */
    @Test
    fun removeNotFoundSaysAlreadySent() = runTest {
        val (chat, channel) = store(backgroundScope)
        channel.removal = GatewayErrorBody(code = GatewayErrorCode.notFound, message = "that message is not queued")
        chat.removeQueued("q-a").join()
        assertEquals("That message has already been sent.", chat.errorMessage, "never the device's bare not_found")
        assertEquals(listOf("q-b", "q-c"), chat.timeline.queue.map { it.id }, "and the row stays gone")
    }

    /** A Remove the device refuses puts the row back where it stood. */
    @Test
    fun refusedRemoveComesBack() = runTest {
        val failures = listOf<Throwable>(
            GatewayErrorBody(code = GatewayErrorCode.deviceOffline, message = "That device is offline."),
            GatewayErrorBody(code = GatewayErrorCode.conflict, message = "busy"),
            TransportError.RequestTimedOut,
        )
        for (failure in failures) {
            val (chat, channel) = store(backgroundScope)
            channel.removal = failure
            val removing = chat.removeQueued("q-b")
            assertEquals(listOf("q-a", "q-c"), chat.timeline.queue.map { it.id })
            removing.join()
            assertEquals(listOf("q-a", "q-b", "q-c"), chat.timeline.queue.map { it.id },
                         "the device still holds it, and nothing else would bring it back: $failure")
            assertEquals(3, chat.session.queued)
            assertNotNull(chat.errorMessage)
        }
    }

    /** A snapshot that arrived while the Remove was out knows better than the row. */
    @Test
    fun refusedRemoveDefersToANewerSnapshot() = runTest {
        val (chat, channel) = store(backgroundScope)
        channel.removal = GatewayErrorBody(code = GatewayErrorCode.deviceOffline, message = "That device is offline.")
        channel.holding = setOf("session.queue_remove")
        val removing = chat.removeQueued("q-b")
        settle { channel.requests.size == 1 }
        chat.receive(AppFrame.SessionEvent(sessionID = "s", deviceID = "d",
                                           event = SessionEvent(seq = 2, ts = 2, kind = SessionEvent.queueKind,
                                                                body = SessionEventBody.Queue(QueuePayload(pending = listOf(queue[2]))))))
        channel.release()
        removing.join()
        assertEquals(listOf("q-c"), chat.timeline.queue.map { it.id }, "the newer snapshot stands")
        assertEquals("That device is offline.", chat.errorMessage)
    }

    /** A tap takes the message out of the line first, then puts its words in the field. */
    @Test
    fun beginTakesItOutFirst() = runTest {
        val (chat, channel) = store(backgroundScope)
        chat.draft = "a note of my own"
        chat.beginEdit(queue[1])

        assertEquals(listOf("session.queue_remove"), channel.requests.map { it.type })
        assertEquals("q-b", channel.requests.firstOrNull()?.body?.get("queued_id")?.stringValue)
        assertEquals(QueuedEdit(ts = 2_000, original = "Add a regression test.", aside = "a note of my own"), chat.queuedEdit)
        assertEquals("Add a regression test.", chat.draft, "the entry's words, not appended to the draft")
    }

    /** A message the device took first opens nothing, and one line says so. */
    @Test
    fun notFoundOpensNothing() = runTest {
        val (chat, channel) = store(backgroundScope)
        channel.removal = GatewayErrorBody(code = GatewayErrorCode.notFound, message = "that message is not queued")
        chat.draft = "mine"
        chat.beginEdit(queue[0])

        assertEquals("That message has already been sent.", chat.errorMessage)
        assertNull(chat.queuedEdit)
        assertEquals("mine", chat.draft, "the field is untouched")
    }

    /** A message that carries files is removed, never edited. */
    @Test
    fun filesAreRemoveOnly() = runTest {
        val (chat, channel) = store(backgroundScope)
        assertFalse(chat.canEdit(queue[2]))
        chat.beginEdit(queue[2])
        assertTrue(channel.requests.isEmpty(), "nothing is taken out of the line")
        assertNull(chat.queuedEdit)
    }

    /** Where the composer cannot send, every row is Remove only. */
    @Test
    fun disabledComposerIsRemoveOnly() = runTest {
        val (chat, _) = store(backgroundScope)
        assertTrue(chat.canEdit(queue[0]))
        chat.deviceOnline = false
        assertFalse(chat.canEdit(queue[0]), "the device is offline")

        val (held, _) = store(backgroundScope, control = SessionControl.terminal)
        assertFalse(held.canEdit(queue[0]), "the terminal holds the session")
    }

    /** One message is edited at a time. */
    @Test
    fun oneAtATime() = runTest {
        val (chat, channel) = store(backgroundScope)
        chat.beginEdit(queue[0])
        assertFalse(chat.canEdit(queue[1]))
        chat.beginEdit(queue[1])
        assertEquals(1, channel.requests.size, "the second tap takes nothing out")
        assertEquals(1_000L, chat.queuedEdit?.ts)
    }

    // Putting it back

    /** Send puts the edited words back under the entry's ts, even behind a steering agent. */
    @Test
    fun sendRequeuesInPlace() = runTest {
        val (chat, channel) = store(backgroundScope, agent = steering)
        chat.draft = "a note of my own"
        chat.beginEdit(queue[1])
        assertFalse(chat.steersRunningTurn, "neither the status line nor the placeholder promises a steer")
        assertEquals("Queue", chat.editingSendLabel, "the primary names what it will do")

        chat.draft = "Add a regression test for the logout race too."
        val outcome = chat.send()

        assertEquals(ChatStore.SendOutcome.accepted, outcome)
        val body = channel.sends.lastOrNull()?.body
        assertEquals("queue", body?.get("mode")?.stringValue)
        assertEquals(jsonOf(2_000), body?.get("queue_ts"))
        assertEquals("Add a regression test for the logout race too.", body?.get("text")?.stringValue)
        assertNull(chat.queuedEdit)
        assertEquals("a note of my own", chat.draft, "and the words set aside come back")
    }

    /** On an idle session the primary reads Send, and the edit still goes as a queued message. */
    @Test
    fun idleSendsAtOnce() = runTest {
        val (chat, channel) = store(backgroundScope, state = SessionState.idle)
        chat.beginEdit(queue[0])
        assertEquals("Send", chat.editingSendLabel)
        chat.send()
        assertEquals("queue", channel.sends.lastOrNull()?.body?.get("mode")?.stringValue)
        assertEquals(jsonOf(1_000), channel.sends.lastOrNull()?.body?.get("queue_ts"))
    }

    /** While the words are on their way back, nothing can send them twice. */
    @Test
    fun putBackHoldsStill() = runTest {
        val (chat, channel) = store(backgroundScope)
        chat.draft = "mine"
        chat.beginEdit(queue[1])
        chat.draft = "edited"
        channel.holding = setOf("session.send")
        val sending = async { chat.send() }
        settle { channel.sends.size == 1 }

        assertTrue(chat.isReturningEdit)
        assertEquals("edited", chat.draft, "the field keeps the words while they are out")
        assertFalse(chat.canSend, "the button's place is a spinner")
        assertEquals(ComposerPrimarySlot.working,
                     ComposerPrimarySlot.of(voice = VoiceInputPhase.idle, polish = chat.polishPhase, returning = chat.isReturningEdit))
        assertFalse(chat.canCancelEdit, "and Cancel waits with it")
        chat.cancelEdit()
        assertEquals(1, channel.sends.size, "a Cancel tapped meanwhile sends nothing")

        channel.release()
        sending.await()
        assertEquals(1, channel.sends.size, "exactly one session.send went out")
        assertFalse(chat.isReturningEdit)
        assertNull(chat.queuedEdit)
        assertEquals("mine", chat.draft)
    }

    /** While editing, the status line says queued, as the button does, even on a steering agent. */
    @Test
    fun statusLineAgreesWithTheButton() = runTest {
        val (chat, _) = store(backgroundScope, agent = steering)
        chat.beginEdit(queue[0])
        // The device's snapshot once the line has emptied behind the edit.
        chat.receive(AppFrame.SessionEvent(sessionID = "s", deviceID = "d",
                                           event = SessionEvent(seq = 2, ts = 2, kind = SessionEvent.queueKind,
                                                                body = SessionEventBody.Queue(QueuePayload(pending = emptyList())))))
        assertEquals("Working · your message will be queued", chat.statusLine)
        chat.cancelEdit()
        assertEquals("Working · your message will steer the turn", chat.statusLine,
                     "and once the edit is over the agent steers again")
    }

    /** A refused send keeps editing, the words in the field and the other draft aside. */
    @Test
    fun refusalKeepsEditing() = runTest {
        val (chat, channel) = store(backgroundScope)
        chat.draft = "mine"
        chat.beginEdit(queue[1])
        chat.draft = "edited"
        channel.sending = Result.failure(GatewayErrorBody(code = GatewayErrorCode.deviceOffline, message = "That device is offline."))

        assertEquals(ChatStore.SendOutcome.refused, chat.send())
        assertEquals("mine", chat.queuedEdit?.aside)
        assertEquals("edited", chat.draft)
        assertEquals("That device is offline.", chat.errorMessage)
    }

    /** Cancel puts the original words back in their place, and the other draft in the field. */
    @Test
    fun cancelRequeuesTheOriginal() = runTest {
        val (chat, channel) = store(backgroundScope)
        chat.draft = "mine"
        chat.beginEdit(queue[1])
        chat.draft = "half edited"
        chat.cancelEdit()

        val body = channel.sends.lastOrNull()?.body
        assertEquals("Add a regression test.", body?.get("text")?.stringValue)
        assertEquals("queue", body?.get("mode")?.stringValue)
        assertEquals(jsonOf(2_000), body?.get("queue_ts"))
        assertNull(chat.queuedEdit)
        assertEquals("mine", chat.draft, "the edited words go; the ones set aside return")
    }

    /** A refused Cancel changes nothing. */
    @Test
    fun refusedCancelChangesNothing() = runTest {
        val (chat, channel) = store(backgroundScope)
        chat.draft = "mine"
        chat.beginEdit(queue[1])
        chat.draft = "half edited"
        channel.sending = Result.failure(GatewayErrorBody(code = GatewayErrorCode.conflict, message = "No."))
        chat.cancelEdit()

        assertEquals("mine", chat.queuedEdit?.aside)
        assertEquals("half edited", chat.draft)
    }

    /** Interrupt & send leaves the line: no queue_ts, and the edit is over. */
    @Test
    fun interruptLeavesTheLine() = runTest {
        val (chat, channel) = store(backgroundScope)
        chat.draft = "mine"
        chat.beginEdit(queue[1])
        chat.draft = "now, please"
        channel.sending = Result.success(SendResult(accepted = SendAcceptance.sent))
        chat.send(mode = SendMode.interrupt)

        val body = channel.sends.lastOrNull()?.body
        assertEquals("interrupt", body?.get("mode")?.stringValue)
        assertNull(body?.get("queue_ts"))
        assertNull(chat.queuedEdit)
        assertEquals("mine", chat.draft)
    }

    /** An unconfirmed send ends the edit, and Retry keeps the message's place. */
    @Test
    fun retryKeepsQueueTs() = runTest {
        val (chat, channel) = store(backgroundScope)
        chat.draft = "mine"
        chat.beginEdit(queue[1])
        chat.draft = "edited"
        channel.sending = Result.failure(TransportError.DeliveryUncertain)

        assertEquals(ChatStore.SendOutcome.uncertain, chat.send())
        assertNull(chat.queuedEdit)
        assertEquals("mine", chat.draft)
        val pending = assertNotNull(chat.unconfirmedSend)
        assertEquals(2_000L, pending.queueTs)

        channel.sending = Result.success(SendResult(accepted = SendAcceptance.queued, queuedID = pending.id))
        chat.retry(pending)
        val retried = channel.sends.lastOrNull()
        assertEquals(pending.id, retried?.id, "the same request")
        assertEquals("queue", retried?.body?.get("mode")?.stringValue)
        assertEquals(jsonOf(2_000), retried?.body?.get("queue_ts"), "going back to the same place")
    }

    // A message and nothing else

    /** While editing, the command panel is not offered. */
    @Test
    fun noCommandPanelWhileEditing() = runTest {
        val (chat, _) = store(backgroundScope, agent = steering)
        chat.draft = "/re"
        assertNotNull(chat.commandDraft, "a slash opens the panel on an ordinary draft")
        chat.draft = ""
        chat.beginEdit(queue[0])
        chat.draft = "/review the diff"
        assertNull(chat.commandDraft, "the edited message is a message and nothing else")
        assertTrue(chat.commandRows.isEmpty())
    }

    /** A pending question waits for the field rather than taking it. */
    @Test
    fun questionWaitsForTheField() = runTest {
        val (chat, channel) = store(backgroundScope, state = SessionState.needsInput)
        chat.receive(AppFrame.SessionEvent(sessionID = "s", deviceID = "d",
                                           event = SessionEvent(seq = 2, ts = 2, kind = SessionEvent.questionKind, blockID = "q-1",
                                                                body = SessionEventBody.Question(DemoFixtures.sharedQuestion))))
        assertNotNull(chat.pendingQuestion)

        chat.beginEdit(queue[0])
        assertNull(chat.pendingQuestion, "the field is the edited message")
        assertTrue(chat.allowsAnswers, "and the card itself stays live")
        chat.draft = "edited"
        chat.send()
        assertEquals(1, channel.sends.size, "Send sent the message rather than an answer")
        assertFalse(channel.requests.any { it.type == "session.answer" })
        assertNotNull(chat.pendingQuestion, "once the edit is over the question has the field again")
    }

    /** An edit left open when the conversation closed comes back with it. */
    @Test
    fun resumedEditStillSendsInPlace() = runTest {
        val (chat, channel) = store(backgroundScope)
        chat.draft = "edited"
        chat.resumeEdit(QueuedEdit(ts = 2_000, original = "Add a regression test.", aside = "mine"))
        chat.send()
        assertEquals(jsonOf(2_000), channel.sends.lastOrNull()?.body?.get("queue_ts"))
        assertEquals("mine", chat.draft)
    }

    /** A channel that answers `session.queue_remove` and `session.send` as the test says, and records every request it was given. */
    private class QueueChannel : InertChannel() {
        val requests = mutableListOf<GatewayRequest>()

        /** What `session.queue_remove` fails with, or null for `{}`. */
        var removal: Throwable? = null

        /** What `session.send` answers. */
        var sending: Result<SendResult> = Result.success(SendResult(accepted = SendAcceptance.queued))

        /** Request types that wait for [release] before they answer. */
        var holding: Set<String> = emptySet()
        private var waiting: CompletableDeferred<Unit>? = null

        val sends: List<GatewayRequest> get() = requests.filter { it.type == "session.send" }

        fun release() {
            waiting?.complete(Unit)
            waiting = null
        }

        override suspend fun request(request: GatewayRequest): JsonElement {
            requests.add(request)
            if (request.type in holding) {
                val gate = CompletableDeferred<Unit>()
                waiting = gate
                gate.await()
            }
            return when (request.type) {
                "session.queue_remove" -> {
                    removal?.let { throw it }
                    JSONValue.emptyObject
                }
                "session.send" -> JSONValue.encode(sending.getOrThrow())
                else -> JSONValue.emptyObject
            }
        }
    }
}
