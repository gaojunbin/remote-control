@file:OptIn(ExperimentalCoroutinesApi::class)

package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.MessageDelivery
import com.junbingao.remotecontrol.core.protocol.PreferencePatch
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionResult
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.SubscribeResult
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.state.request
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What the demo device does with a turn: sends, its queue, attached terminals, commands, resumes and closes. */
class DemoTurnTests {
    /** Amendment A43 at the gateway: a held message taken out to be edited goes back to the place it left. */
    @Test
    fun queuedEditGoesBackToItsPlace() = runTest {
        val gateway = demoGateway(holdsQueue = true)
        val log = EventLog(backgroundScope, gateway)
        val live = DemoFixtures.liveSessionID
        val opened = gateway.request(GatewayRequest.subscribe(sessionID = live), SubscribeResult.serializer())
        val held = assertNotNull(opened.queue, "the subscribe reply carries the line (A6)").pending
        assertEquals(listOf("demo-queued-suite", "demo-queued-regression", "demo-queued-evidence"), held.map { it.id })
        assertEquals(2, held.last().attachments)
        assertEquals(3, opened.session.queued)

        val middle = held[1]
        gateway.request(GatewayRequest.queueRemove(sessionID = live, queuedID = middle.id))
        runCurrent()
        assertEquals(listOf("demo-queued-suite", "demo-queued-evidence"), log.queue(live).map { it.id })
        val edited = "Add a regression test for the refresh race, and one for logout."
        val sent = GatewayRequest.send(id = middle.id, sessionID = live, text = edited, mode = SendMode.queue, queueTs = middle.ts)
        assertEquals(SendResult(accepted = SendAcceptance.queued, queuedID = middle.id), gateway.request(sent, SendResult.serializer()))
        runCurrent()
        val line = log.queue(live)
        assertEquals(listOf("demo-queued-suite", middle.id, "demo-queued-evidence"), line.map { it.id })
        assertEquals(middle.ts, line[1].ts, "under the ts it left")
        assertEquals(edited, line[1].text)
        assertEquals(3, log.updates(live).last().queued)

        // The turn ends, and the device delivers the line in order, each message a turn of its own.
        gateway.request(GatewayRequest.stop(sessionID = live))
        settle()
        val delivered = log.events(live).filter { it.userMessage?.source == EventSource.queue }
        assertEquals(listOf("demo-queued-suite", middle.id, "demo-queued-evidence"), delivered.map { it.blockID })
        assertEquals(edited, delivered[1].userMessage?.text)
        assertEquals(2, delivered[2].userMessage?.attachments?.size, "the files it held go with it")
        assertTrue(log.queue(live).isEmpty())
        assertEquals(0, log.updates(live).last().queued)
        assertEquals(SessionState.idle, log.updates(live).last().state)
    }

    /**
     * Amendments A10, A19, A20 and A40 on the attached Claude session: a busy terminal refuses in the
     * device's words, a question answered here resolves as this app's, a change is typed in, and a
     * message is a queue entry until the CLI takes it.
     */
    @Test
    fun sharedClaudeTypesAndInjects() = runTest {
        val gateway = demoGateway()
        val log = EventLog(backgroundScope, gateway)
        val shared = DemoFixtures.sharedSessionID
        gateway.request(GatewayRequest.subscribe(sessionID = shared))

        // The terminal switches model (A17), then asks a question of its own (A20).
        advanceTimeBy(1.seconds)
        assertEquals("claude-opus-4-1", log.events(shared).first().meta?.model)
        assertEquals(SessionState.needsInput, log.updates(shared).last().state)
        val busy = assertFailsWith<GatewayErrorBody> { gateway.request(GatewayRequest.set(sessionID = shared, effort = "medium")) }
        assertEquals(GatewayErrorCode.conflict, busy.code)
        assertEquals(DemoGateway.terminalIsBusy, busy.message)
        assertEquals("answer the prompt first",
                     assertFailsWith<GatewayErrorBody> { gateway.request(GatewayRequest.stop(sessionID = shared)) }.message)

        gateway.request(GatewayRequest.answer(sessionID = shared, requestID = "demo-question-shared",
                                              answers = mapOf("q1" to QuestionAnswer.Options(listOf("phone")))))
        runCurrent()
        val answered = assertNotNull(log.events(shared).last { it.question != null }.question)
        assertEquals(RequestStatus.resolved, answered.status)
        assertEquals(EventSource.remote, answered.by)
        assertEquals(SessionState.idle, log.updates(shared).last().state)

        // A key the shim can type waits while it is typed; one it cannot is refused.
        val start = currentTime
        val set = gateway.request(GatewayRequest.set(sessionID = shared, effort = "medium"), SessionResult.serializer())
        assertEquals(1_500, currentTime - start, "the device types it and reads the answer back first")
        assertEquals("medium", set.session.effort)
        assertEquals("Change it in the terminal.", assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.set(sessionID = shared, permissionMode = "plan"))
        }.message)

        // A message waits as a queue entry and nothing else, then the CLI takes it and asks.
        log.clear()
        val send = GatewayRequest.send(sessionID = shared, text = "Tighten the wording.")
        assertEquals(SendResult(accepted = SendAcceptance.queued, queuedID = send.id), gateway.request(send, SendResult.serializer()))
        runCurrent()
        assertEquals(listOf(send.id), log.queue(shared).map { it.id })
        assertTrue(log.events(shared).none { it.blockID == send.id }, "no block while it waits (A19)")
        advanceTimeBy(2_400.milliseconds)
        runCurrent()
        assertEquals(MessageDelivery.delivered, log.events(shared).single { it.blockID == send.id }.userMessage?.delivery)
        assertTrue(log.queue(shared).isEmpty())
        advanceTimeBy(900.milliseconds)
        runCurrent()
        assertEquals("demo-approval-shared", log.events(shared).last { it.approval != null }.approval?.requestID)
        assertEquals(SessionState.needsApproval, log.updates(shared).last().state)
        gateway.request(GatewayRequest.approve(sessionID = shared, requestID = "demo-approval-shared", optionID = "allow"))
        settle()
        assertEquals(RequestStatus.resolved, log.events(shared).last { it.approval != null }.approval?.status)
        assertEquals(SessionState.idle, log.updates(shared).last().state)
        gateway.disconnect()
    }

    /**
     * Amendments A11 and A14 on the shared Codex thread: a message steers the running turn and its
     * block waits for the agent's next step; the daemon relays settings, refuses an option the
     * block never offered, and interrupts.
     */
    @Test
    fun codexSteersARunningThread() = runTest {
        val gateway = demoGateway()
        val log = EventLog(backgroundScope, gateway)
        val codex = DemoFixtures.codexSharedSessionID
        val send = GatewayRequest.send(sessionID = codex, text = "also check the drawer's tests")
        assertEquals(SendAcceptance.steered, gateway.request(send, SendResult.serializer()).accepted)
        advanceTimeBy(DemoGateway.defaultEchoDelay)
        runCurrent()
        val (said, read) = log.events(codex)
        assertEquals("The typecheck is clean now that the drawer passes `effort` through again.", said.streamText?.text,
                     "the turn finishes its sentence first")
        assertEquals(send.id, read.blockID)
        assertEquals(MessageDelivery.delivered, read.userMessage?.delivery)
        settle()
        assertEquals(SessionState.idle, log.updates(codex).last().state)

        assertEquals("high", gateway.request(GatewayRequest.set(sessionID = codex, effort = "high"),
                                             SessionResult.serializer()).session.effort)
        assertEquals("priority", gateway.request(GatewayRequest.set(sessionID = codex, speed = SpeedChange.Tier("priority")),
                                                 SessionResult.serializer()).session.speed)
        assertNull(gateway.request(GatewayRequest.set(sessionID = codex, speed = SpeedChange.Standard),
                                   SessionResult.serializer()).session.speed)
        assertEquals(GatewayErrorCode.badRequest, assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.approve(sessionID = codex, requestID = "demo-approval-codex",
                                                   optionID = ApprovalPayload.elsewhereOptionID))
        }.code)
        gateway.request(GatewayRequest.stop(sessionID = codex))
        runCurrent()
        val stopped = log.events(codex).last { it.kind == SessionEvent.turnCompletedKind }
        assertEquals(StopReason.interrupted, (stopped.body as SessionEventBody.TurnCompleted).payload.stopReason)
    }

    /** Amendment A28: a message for the leader's session is sent, not held, and `interrupt` ends the running turn first. */
    @Test
    fun grokLeaderInterruptsTheTurn() = runTest {
        val gateway = demoGateway()
        val log = EventLog(backgroundScope, gateway)
        val grok = DemoFixtures.grokSharedSessionID
        val send = GatewayRequest.send(sessionID = grok, text = "cap it at thirty seconds instead", mode = SendMode.interrupt)
        assertEquals(SendResult(accepted = SendAcceptance.sent), gateway.request(send, SendResult.serializer()))
        runCurrent()
        val (ended, message) = log.events(grok)
        val turn = (ended.body as SessionEventBody.TurnCompleted).payload
        assertEquals("demo-turn-grok-shared", turn.turnID)
        assertEquals(StopReason.interrupted, turn.stopReason)
        assertEquals("cap it at thirty seconds instead", message.userMessage?.text)
        assertEquals("low", gateway.request(GatewayRequest.set(sessionID = grok, effort = "low"),
                                            SessionResult.serializer()).session.effort)
        settle()
        assertEquals(SessionState.idle, log.updates(grok).last().state)
    }

    /**
     * Amendment A27: a state change reads as a notice, information a terminal would have printed as
     * a tool call titled with the command, anything else as a turn; and the refusals say why.
     */
    @Test
    fun commandsPlayAsTheDeviceReports() = runTest {
        val gateway = demoGateway()
        val log = EventLog(backgroundScope, gateway)
        val pi = DemoFixtures.piSessionID
        gateway.request(GatewayRequest.command(id = "c-1", sessionID = pi, name = "compact", argument = "keep the decisions"))
        runCurrent()
        val (echo, notice) = log.events(pi)
        assertEquals("c-1", echo.blockID)
        assertEquals("/compact keep the decisions", echo.userMessage?.text)
        assertEquals("Context was compacted; earlier turns are summarised.", (notice.body as SessionEventBody.Notice).payload.text)

        val codex = DemoFixtures.codexSharedSessionID
        assertEquals("Wait for the turn to finish.", assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.command(sessionID = codex, name = "usage"))
        }.message)
        gateway.request(GatewayRequest.stop(sessionID = codex))
        gateway.request(GatewayRequest.command(sessionID = codex, name = "usage"))
        runCurrent()
        val usage = assertNotNull(log.events(codex).last().toolCall)
        assertEquals("/usage", usage.title)
        assertEquals(ToolKind.other, usage.kind)
        assertEquals(true, usage.output?.contains("5-hour window"))

        gateway.request(GatewayRequest.command(sessionID = codex, name = "review"))
        settle()
        val review = log.events(codex).takeLast(6).map { (it.body as? SessionEventBody.Notice)?.payload?.text ?: it.streamText?.text ?: it.kind }
        assertEquals(listOf("Review started",
                            "Reviewed 6 changed files. One finding: `SessionSettings` drops `effort` when the drawer is closed.",
                            "Review finished", SessionEvent.turnCompletedKind, SessionEvent.statusKind), review.drop(1))

        assertEquals(GatewayErrorCode.notFound, assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.command(sessionID = codex, name = "nope"))
        }.code)
        assertEquals(GatewayErrorCode.conflict, assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.command(sessionID = DemoFixtures.grokSessionID, name = "hooks-list"))
        }.code)
        assertEquals("claude takes no commands from here.", assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.commands(sessionID = DemoFixtures.attachHintSessionID))
        }.message)
    }

    /**
     * Amendment A35: a resume is moved, refused outside its bounds, cancelled once, and every
     * pending one ends when the switch goes off.
     */
    @Test
    fun resumeIsScheduledMovedAndCancelled() = runTest {
        val gateway = demoGateway()
        val log = EventLog(backgroundScope, gateway)
        val paused = DemoFixtures.pausedSessionID
        fun inHours(hours: Long): Instant = Instant.ofEpochMilli(System.currentTimeMillis() + hours * 3_600_000)

        val twoHours = inHours(2)
        val moved = gateway.request(GatewayRequest.resumeSet(sessionID = paused, at = twoHours), SessionResult.serializer())
        assertEquals(twoHours.toEpochMilli(), assertNotNull(moved.session.resume).at)
        assertEquals(300, moved.session.resume.windowMinutes, "the window it was waiting on is kept")
        assertEquals(GatewayErrorCode.badRequest, assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.resumeSet(sessionID = paused, at = Instant.now().plusSeconds(10)))
        }.code)
        assertEquals("Wait for the turn to finish.", assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.resumeSet(sessionID = DemoFixtures.liveSessionID, at = inHours(2)))
        }.message)
        assertEquals("Controlled by the terminal; take over first.", assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.resumeSet(sessionID = DemoFixtures.attachHintSessionID, at = inHours(2)))
        }.message)

        assertNull(gateway.request(GatewayRequest.resumeCancel(sessionID = paused), SessionResult.serializer()).session.resume)
        gateway.request(GatewayRequest.resumeCancel(sessionID = paused))
        gateway.request(GatewayRequest.resumeSet(sessionID = paused, at = inHours(3)))
        gateway.request(GatewayRequest.resumeSet(sessionID = DemoFixtures.piSessionID, at = inHours(2)))
        gateway.patchPreferences(PreferencePatch(resumeAfterLimit = false))
        runCurrent()
        val statuses = log.events(paused).mapNotNull { it.resume?.status }
        assertEquals(listOf(ResumeStatus.rescheduled, ResumeStatus.cancelled, ResumeStatus.scheduled, ResumeStatus.cancelled),
                     statuses, "a second cancel says nothing")
        assertEquals("the switch was turned off", log.events(paused).last().resume?.reason)
        assertEquals(listOf(ResumeStatus.scheduled, ResumeStatus.cancelled), log.events(DemoFixtures.piSessionID).mapNotNull { it.resume?.status })
        assertEquals(false, gateway.preferences().preferences.resumeAfterLimit)
    }

    /**
     * Amendment A39: archiving a session the device drives closes it — archived, unowned and stopped
     * in one summary — while unarchiving only clears the flag; and a created session is listed at once.
     */
    @Test
    fun closingStopsAndUnownsASession() = runTest {
        val gateway = demoGateway()
        val log = EventLog(backgroundScope, gateway)
        val closed = gateway.request(GatewayRequest.archive(sessionID = DemoFixtures.piSessionID, archived = true),
                                     SessionResult.serializer()).session
        assertTrue(closed.archived)
        assertEquals(SessionControl.none, closed.control)
        assertEquals(SessionState.stopped, closed.state)
        assertNull(closed.turn)
        val revived = gateway.request(GatewayRequest.archive(sessionID = DemoFixtures.revivedSessionID, archived = false),
                                      SessionResult.serializer()).session
        assertEquals(false, revived.archived)
        assertEquals(SessionState.stopped, revived.state, "only the flag moved")

        val created = gateway.request(GatewayRequest.createSession(deviceID = DemoFixtures.macDeviceID, agent = "codex",
                                                                   cwd = "/Users/me/dev/x", model = "gpt-5.4-codex",
                                                                   title = "Fresh"), SessionResult.serializer()).session
        assertTrue(created.sessionID.startsWith("demo-"))
        assertEquals(SessionControl.remote, created.control)
        assertEquals("gpt-5.4-codex", created.model)
        runCurrent()
        assertEquals(created, log.updates(created.sessionID).single())
        assertEquals(created.sessionID, gateway.sessions().first().sessionID, "a new session is listed first")
    }
}
