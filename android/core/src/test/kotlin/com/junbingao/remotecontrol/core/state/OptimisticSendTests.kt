package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.OutboundAttachment
import com.junbingao.remotecontrol.core.protocol.QueuePayload
import com.junbingao.remotecontrol.core.protocol.QueuedMessage
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Amendment A12: the app mints the `session.send` request id, the device echoes it as the
 * `user_message` block id, and the message is therefore on screen before the request has left.
 * These cover what happens to that row for every outcome the send can have.
 */
class OptimisticSendTests {
    private fun session(state: SessionState = SessionState.idle): Session =
        Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp", state = state,
                control = SessionControl.remote)

    private fun message(text: String, id: String, source: EventSource = EventSource.remote, seq: Int): SessionEvent =
        SessionEvent(seq = seq, ts = 0, kind = SessionEvent.userMessageKind, blockID = id,
                     body = SessionEventBody.UserMessage(UserMessagePayload(text = text, source = source)))

    // The row appears before the request is answered

    /** The message is in the transcript while the send is still in flight. */
    @Test
    fun shownBeforeTheReply() = runTest {
        val channel = HeldSendChannel()
        val chat = ChatStore(session = session(), channel = channel, tasks = backgroundScope)
        chat.draft = "ship it"

        val sending = async { chat.send() }
        channel.waitForSend()

        assertTrue(chat.draft.isEmpty())
        assertEquals(1, chat.timeline.roots.size)
        val row = chat.timeline.roots.lastOrNull()
        assertEquals("ship it", row?.pending?.text)
        assertEquals("ship it", row?.userMessage?.text)
        assertEquals(EventSource.remote, row?.userMessage?.source)
        // The row is the request id, which is what the device will echo.
        assertEquals(channel.lastSendID, row?.id)

        channel.release(Result.success(SendResult(accepted = SendAcceptance.sent)))
        sending.await()
        assertEquals(1, chat.timeline.optimistic.size, "still waiting for the device's own event")
    }

    /** Attachment metadata rides along, and the bytes do not. */
    @Test
    fun attachmentMetadata() = runTest {
        val channel = HeldSendChannel()
        val chat = ChatStore(session = session(), channel = channel, tasks = backgroundScope)
        chat.draft = "look at this"

        val sending = async {
            chat.send(attachments = listOf(OutboundAttachment(name = "shot.png", mime = "image/png",
                                                              data = byteArrayOf(1, 2, 3, 4))))
        }
        channel.waitForSend()
        val attachment = chat.timeline.roots.lastOrNull()?.userMessage?.attachments?.firstOrNull()
        assertEquals("shot.png", attachment?.name)
        assertEquals("image/png", attachment?.mime)
        assertEquals(4, attachment?.size)

        channel.release(Result.success(SendResult(accepted = SendAcceptance.sent)))
        sending.await()
    }

    // Reconciliation

    /** The device's event under the same id replaces the row rather than adding one. */
    @Test
    fun replacedByID() {
        val timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "ship it"))
        assertEquals(1, timeline.roots.size)

        timeline.apply(message("ship it", id = "req-1", seq = 1))
        assertTrue(timeline.optimistic.isEmpty())
        assertEquals(1, timeline.roots.size)
        assertNull(timeline.roots.firstOrNull()?.pending)
        assertEquals("req-1", timeline.roots.firstOrNull()?.id)
    }

    /** A device that mints its own ids is reconciled by text and source. */
    @Test
    fun reconciledByText() {
        val timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "ship it"))

        timeline.apply(message("ship it", id = "device-block", seq = 1))
        assertTrue(timeline.optimistic.isEmpty())
        assertEquals(1, timeline.roots.size)
        assertEquals("device-block", timeline.roots.firstOrNull()?.id)
    }

    /** Only one row is reconciled per event, and only a remote message reconciles at all. */
    @Test
    fun reconcilesOneRowAtATime() {
        val timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "again"))
        timeline.addOptimistic(OptimisticMessage(id = "req-2", text = "again"))

        // The same words typed in the terminal are a different message.
        timeline.apply(message("again", id = "t-1", source = EventSource.terminal, seq = 1))
        assertEquals(2, timeline.optimistic.size)

        timeline.apply(message("again", id = "device-block", seq = 2))
        assertEquals(listOf("req-2"), timeline.optimistic.map { it.id })
    }

    /** Adding the same send twice keeps one row, so Retry reuses it. */
    @Test
    fun addIsIdempotent() {
        val timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "ship it"))
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "ship it"))
        assertEquals(1, timeline.optimistic.size)

        timeline.apply(message("ship it", id = "req-1", seq = 1))
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "ship it"))
        assertTrue(timeline.optimistic.isEmpty(), "a block the device has already sent is never re-added")
        assertEquals(1, timeline.roots.size)
    }

    // Outcomes

    /** A queued send hands its row to the queue. */
    @Test
    fun queuedRemovesTheRow() = runTest {
        val channel = HeldSendChannel()
        val chat = ChatStore(session = session(state = SessionState.running), channel = channel, tasks = backgroundScope)
        chat.draft = "then run the suite"

        val sending = async { chat.send() }
        channel.waitForSend()
        assertEquals(1, chat.timeline.roots.size)

        channel.release(Result.success(SendResult(accepted = SendAcceptance.queued, queuedID = channel.lastSendID)))
        sending.await()
        assertTrue(chat.timeline.optimistic.isEmpty())
        assertTrue(chat.timeline.roots.isEmpty(), "the queue row above the composer stands for it now")
    }

    /** A queue snapshot retires the row of a message it names. */
    @Test
    fun queueSnapshotRetiresTheRow() {
        val timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "then run the suite"))
        timeline.apply(SessionEvent(seq = 1, ts = 0, kind = SessionEvent.queueKind,
                                    body = SessionEventBody.Queue(QueuePayload(pending = listOf(
                                        QueuedMessage(id = "req-1", text = "then run the suite", ts = 0))))))
        assertTrue(timeline.optimistic.isEmpty())
        assertEquals(1, timeline.queue.size)
    }

    /** A refusal takes the row away and gives the words back. */
    @Test
    fun refusalRestoresTheDraft() = runTest {
        val channel = HeldSendChannel()
        val chat = ChatStore(session = session(), channel = channel, tasks = backgroundScope)
        chat.draft = "take over first"

        val sending = async { chat.send() }
        channel.waitForSend()
        channel.release(Result.failure(GatewayErrorBody(code = GatewayErrorCode.conflict,
                                                        message = "Controlled by the terminal; take over first.")))
        sending.await()

        assertTrue(chat.timeline.optimistic.isEmpty())
        assertTrue(chat.timeline.roots.isEmpty())
        assertEquals("Controlled by the terminal; take over first.", chat.errorMessage)
        assertEquals("take over first", chat.draft)
    }

    /** A refusal never overwrites what is already being typed. */
    @Test
    fun refusalLeavesANewDraftAlone() = runTest {
        val channel = HeldSendChannel()
        val chat = ChatStore(session = session(), channel = channel, tasks = backgroundScope)
        chat.draft = "the first one"

        val sending = async { chat.send() }
        channel.waitForSend()
        chat.draft = "already typing the next one"
        channel.release(Result.failure(GatewayErrorBody(code = GatewayErrorCode.unsupported, message = "No.")))
        sending.await()

        assertEquals("already typing the next one", chat.draft)
        assertTrue(chat.timeline.roots.isEmpty())
    }

    /**
     * The composer puts a refused send's attachments back, so it has to be told which outcome it is
     * looking at rather than infer it from the field (`docs/DESIGN.md` § "The composer" → **A draft
     * belongs to its session**).
     */
    @Test
    fun sendReportsItsOutcome() = runTest {
        val channel = HeldSendChannel()
        val chat = ChatStore(session = session(), channel = channel, tasks = backgroundScope)

        chat.draft = "first"
        var sending = async { chat.send() }
        channel.waitForSend()
        channel.release(Result.success(SendResult(accepted = SendAcceptance.sent)))
        val accepted = sending.await()
        assertEquals(ChatStore.SendOutcome.accepted, accepted)

        chat.draft = "second"
        sending = async { chat.send() }
        channel.waitForSend()
        channel.release(Result.failure(TransportError.DeliveryUncertain))
        val uncertain = sending.await()
        assertEquals(ChatStore.SendOutcome.uncertain, uncertain)

        chat.draft = "third"
        sending = async { chat.send() }
        channel.waitForSend()
        // The person typed on while the request was out: the words stay theirs, and the outcome
        // still says refused, which is what lets the composer decide about the attachments by
        // itself.
        chat.draft = "typing the next one"
        channel.release(Result.failure(GatewayErrorBody(code = GatewayErrorCode.unsupported, message = "No.")))
        val refused = sending.await()
        assertEquals(ChatStore.SendOutcome.refused, refused)
        assertEquals("typing the next one", chat.draft)

        chat.draft = ""
        val empty = chat.send()
        assertEquals(ChatStore.SendOutcome.empty, empty)
    }

    /** An uncertain delivery keeps the row, and Retry reuses it. */
    @Test
    fun uncertainKeepsTheRow() = runTest {
        val channel = HeldSendChannel()
        val chat = ChatStore(session = session(), channel = channel, tasks = backgroundScope)
        chat.draft = "did that land"

        val sending = async { chat.send() }
        channel.waitForSend()
        val id = channel.lastSendID
        channel.release(Result.failure(TransportError.DeliveryUncertain))
        sending.await()

        assertEquals(1, chat.timeline.roots.size, "the message may well have landed, so it stays")
        assertEquals(id, chat.unconfirmedSend?.id)
        assertTrue(chat.draft.isEmpty(), "the words are in the row, not back in the field")

        val pending = chat.unconfirmedSend ?: return@runTest
        val retry = async { chat.retry(pending) }
        channel.waitForSend()
        assertEquals(id, channel.lastSendID, "a retry reuses the request id")
        assertEquals(1, chat.timeline.roots.size, "and does not add a second row")
        channel.release(Result.success(SendResult(accepted = SendAcceptance.sent)))
        retry.await()
    }

    /** Giving up on an unconfirmed send takes its row with it. */
    @Test
    fun dismissRemovesTheRow() = runTest {
        val channel = HeldSendChannel()
        val chat = ChatStore(session = session(), channel = channel, tasks = backgroundScope)
        chat.draft = "never mind"

        val sending = async { chat.send() }
        channel.waitForSend()
        channel.release(Result.failure(TransportError.RequestTimedOut))
        sending.await()

        val pending = assertNotNull(chat.unconfirmedSend)
        chat.dismiss(pending)
        assertTrue(chat.timeline.roots.isEmpty())
        assertNull(chat.unconfirmedSend)
    }

    // Waiting too long

    /** A send unconfirmed for a minute says so. */
    @Test
    fun unconfirmedAfterAMinute() {
        val now = Instant.now()
        val fresh = OptimisticMessage(id = "a", text = "x", sentAt = now.minusSeconds(5))
        assertFalse(fresh.isUnconfirmed(at = now))
        assertEquals(55.seconds, fresh.remainingBeforeUnconfirmed(at = now))

        val stale = OptimisticMessage(id = "b", text = "y", sentAt = now.minusSeconds(61))
        assertTrue(stale.isUnconfirmed(at = now))
        assertEquals(Duration.ZERO, stale.remainingBeforeUnconfirmed(at = now))

        val timeline = Timeline()
        timeline.addOptimistic(fresh)
        timeline.addOptimistic(stale)
        assertEquals(listOf("b"), timeline.unconfirmedOptimistic(at = now).map { it.id })
    }

    // Reconnects

    /** A resync keeps the row, and the reloaded history does not duplicate it. */
    @Test
    fun resyncNeitherDropsNorDuplicates() {
        val timeline = Timeline()
        timeline.apply(message("an older one", id = "old", seq = 1))
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "ship it"))

        timeline.reset()
        assertTrue(timeline.entries.isEmpty())
        assertEquals(1, timeline.optimistic.size, "a message the user just sent does not vanish")
        assertEquals(1, timeline.roots.size)

        timeline.prependHistory(listOf(message("an older one", id = "old", seq = 1),
                                       message("ship it", id = "req-1", seq = 2)), hasMore = false)
        assertTrue(timeline.optimistic.isEmpty())
        assertEquals(2, timeline.roots.size, "the reloaded copy is the only copy")
    }

    /** A pending row never becomes a history cursor or moves the replay cursor. */
    @Test
    fun pendingRowsCarryNoCursor() {
        val timeline = Timeline()
        timeline.apply(message("an older one", id = "old", seq = 4))
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "ship it"))
        assertEquals(4, timeline.lastSeq)
        assertEquals(4, timeline.oldestSeq)
    }

    /**
     * A channel that stops on `session.send` until the test says what happened, so the state of the
     * transcript mid-flight is observable rather than guessed at.
     */
    private class HeldSendChannel : InertChannel() {
        private var held: CompletableDeferred<Result<SendResult>>? = null
        private var arrived = CompletableDeferred<Unit>()
        var lastSendID = ""
            private set

        override suspend fun request(request: GatewayRequest): JsonElement {
            if (request.type != "session.send") return JSONValue.emptyObject
            lastSendID = request.id
            val outcome = CompletableDeferred<Result<SendResult>>()
            held = outcome
            arrived.complete(Unit)
            return JSONValue.encode(outcome.await().getOrThrow())
        }

        /** Returns once the send has reached the channel and is waiting there. */
        suspend fun waitForSend() {
            arrived.await()
            arrived = CompletableDeferred()
        }

        fun release(outcome: Result<SendResult>) {
            held?.complete(outcome)
            held = null
        }
    }
}
