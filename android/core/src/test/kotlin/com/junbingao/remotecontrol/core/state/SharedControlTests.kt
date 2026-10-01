package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.MessageDelivery
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// The decoding cases of RCCore's suite of this name are in `protocol/SharedControlTests.kt`, and
// `demoFixtures`, which reads the demo's own sessions, is in `demo/SharedControlTests.kt`.

/**
 * Amendment A10: a live CLI owns the session and the device is attached to it. The app types,
 * approves and queues as it would for a session it runs itself, and never offers takeover, Stop,
 * attachments or the terminal's own settings.
 */
class SharedControlTests {
    private fun TestScope.store(state: SessionState, control: SessionControl, agent: AgentInfo? = DemoFixtures.claude,
                                queued: Int = 0): ChatStore {
        val session = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp", state = state,
                              control = control, queued = queued)
        val chat = ChatStore(session = session, channel = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay),
                             tasks = backgroundScope)
        chat.agent = agent
        chat.draft = "hello"
        return chat
    }

    // Composer state

    /** An attached session types exactly like one this app runs. */
    @Test
    fun attachedComposerIsOpen() = runTest {
        val chat = store(state = SessionState.idle, control = SessionControl.shared)
        assertFalse(chat.isReadOnly)
        assertTrue(chat.isAttached)
        assertTrue(chat.canSend)
        assertNull(chat.sendBlockReason)
        assertNull(chat.statusLine)
    }

    /** An attached session never offers takeover. */
    @Test
    fun attachedHidesTakeover() = runTest {
        assertFalse(store(state = SessionState.idle, control = SessionControl.shared).canTakeover)
        assertFalse(store(state = SessionState.running, control = SessionControl.shared).canTakeover)
    }

    /** Takeover is offered only when the agent advertises it. */
    @Test
    fun takeoverNeedsTheCapability() = runTest {
        assertTrue(store(state = SessionState.readonly, control = SessionControl.terminal).canTakeover)
        val without = store(state = SessionState.readonly, control = SessionControl.terminal,
                            agent = AgentInfo(agent = "claude", available = true))
        assertFalse(without.canTakeover)
        assertEquals("Controlled by the terminal", without.statusLine)
        assertFalse(store(state = SessionState.readonly, control = SessionControl.terminal, agent = null).canTakeover)
    }

    /** Stop needs the interrupt capability and an attachment that can interrupt. */
    @Test
    fun attachedStopNeedsSharedInterrupt() = runTest {
        // Claude lists `interrupt`, and since A42 the shim's pseudo-terminal can type Escape; without
        // the shim the channel alone cannot.
        assertTrue(store(state = SessionState.running, control = SessionControl.shared).canStop)
        val unshimmed = AgentInfo(agent = "claude", available = true, capabilities = listOf(AgentCapability.interrupt),
                                  attach = AgentAttach.channel, attachReady = false, sharedInterrupt = false)
        assertFalse(store(state = SessionState.running, control = SessionControl.shared, agent = unshimmed).canStop)

        val codex = AgentInfo(agent = "codex", available = true, capabilities = listOf(AgentCapability.interrupt),
                              attach = AgentAttach.daemon, attachReady = true, sharedInterrupt = true)
        assertTrue(store(state = SessionState.running, control = SessionControl.shared, agent = codex).canStop)
        assertFalse(store(state = SessionState.idle, control = SessionControl.shared, agent = codex).canStop)

        // `shared_interrupt` alone is not enough: the agent must list interrupt.
        val noCapability = AgentInfo(agent = "codex", available = true, capabilities = emptyList(),
                                     attach = AgentAttach.daemon, attachReady = true, sharedInterrupt = true)
        assertFalse(store(state = SessionState.running, control = SessionControl.shared, agent = noCapability).canStop)

        // Nor is the capability alone.
        val noAttachment = AgentInfo(agent = "codex", available = true, capabilities = listOf(AgentCapability.interrupt),
                                     attach = AgentAttach.daemon, attachReady = true)
        assertFalse(store(state = SessionState.running, control = SessionControl.shared, agent = noAttachment).canStop)

        // An unknown agent never offers a stop the device may refuse.
        assertFalse(store(state = SessionState.running, control = SessionControl.shared, agent = null).canStop)
    }

    /** The terminal keeps the permission mode, and gives up the model card. Amendment A40: the terminal keeps only what nothing can be typed for. */
    @Test
    fun attachedSettingsStayInTheTerminal() = runTest {
        val shared = store(state = SessionState.idle, control = SessionControl.shared)
        assertFalse(shared.allowsSettingsChanges(SharedSetting.permissionMode))
        assertTrue(shared.allowsModelCardChanges)
        assertTrue(store(state = SessionState.idle, control = SessionControl.remote).allowsSettingsChanges(SharedSetting.permissionMode))
    }

    /** Attachments cannot be relayed into a live CLI. */
    @Test
    fun attachedRefusesAttachments() = runTest {
        assertFalse(store(state = SessionState.idle, control = SessionControl.shared).allowsAttachments)
        assertFalse(store(state = SessionState.readonly, control = SessionControl.terminal).allowsAttachments)
        assertTrue(store(state = SessionState.idle, control = SessionControl.remote).allowsAttachments)
    }

    /**
     * A question the CLI asked is answered wherever the reader is. Amendment A20: the device raises
     * the question from the hook Claude Code runs beside its own dialog, so the card here is as live
     * as the dialog is.
     */
    @Test
    fun attachedAnswersQuestions() = runTest {
        assertTrue(store(state = SessionState.needsInput, control = SessionControl.shared).allowsAnswers)
        assertTrue(store(state = SessionState.needsInput, control = SessionControl.remote).allowsAnswers)
        // A session the terminal holds outright still takes nothing from here.
        assertFalse(store(state = SessionState.needsInput, control = SessionControl.terminal).allowsAnswers)
        // Approvals are relayed, so they stay answerable while attached.
        assertTrue(store(state = SessionState.needsApproval, control = SessionControl.shared).canSend)
    }

    /**
     * An attached session says what happens to a message, and nothing else. The header already reads
     * `terminal · attached`, so the line above the composer is left to what the header cannot say:
     * what becomes of a message typed into a turn that is already running.
     */
    @Test
    fun attachedStatusLine() = runTest {
        assertEquals("Working · your message will be queued",
                     store(state = SessionState.running, control = SessionControl.shared).statusLine)
        assertEquals("Working · 1 message queued",
                     store(state = SessionState.running, control = SessionControl.shared, queued = 1).statusLine)
        assertEquals("Working · 2 messages queued",
                     store(state = SessionState.running, control = SessionControl.shared, queued = 2).statusLine)
        assertNull(store(state = SessionState.needsApproval, control = SessionControl.shared).statusLine)
        assertNull(store(state = SessionState.needsInput, control = SessionControl.shared).statusLine)
        assertNull(store(state = SessionState.idle, control = SessionControl.shared).statusLine)
    }

    // Transitions

    /**
     * A `meta` event carries `control`; a `status` event rides along only when `state` changed too.
     * The composer follows `control`, never `state`.
     */
    private fun controlChange(seq: Int, control: String): AppFrame = AppFrame(json = jsonObjectOf(
        "type" to "session.event", "session_id" to "s",
        "event" to mapOf("seq" to seq, "ts" to seq, "kind" to SessionEvent.metaKind, "control" to control),
    ))

    /** Attaching and detaching arrive as meta events and move the composer. */
    @Test
    fun controlTransitionsThroughMeta() = runTest {
        val chat = store(state = SessionState.readonly, control = SessionControl.terminal)
        assertTrue(chat.isReadOnly)
        assertFalse(chat.canSend)

        chat.receive(controlChange(2, "shared"))
        assertTrue(chat.isAttached)
        assertFalse(chat.isReadOnly)
        assertTrue(chat.canSend)
        assertNull(chat.attachHint)
        // The meta event says nothing about state, so state is left alone.
        assertEquals(SessionState.readonly, chat.session.state)

        chat.receive(controlChange(3, "terminal"))
        assertFalse(chat.isAttached)
        assertTrue(chat.isReadOnly)
        assertEquals(ChatStore.AttachHint.restartSession, chat.attachHint)

        chat.receive(controlChange(4, "none"))
        assertFalse(chat.isAttached)
        assertFalse(chat.isReadOnly)
        assertTrue(chat.canSend)
        assertNull(chat.attachHint)
    }

    /** A held message is accepted as queued with its own id, never a flag. */
    @Test
    fun sendResultShape() {
        val queued = jsonObjectOf("accepted" to "queued", "queued_id" to "req-1").decode<SendResult>()
        assertEquals(SendAcceptance.queued, queued.accepted)
        assertEquals("req-1", queued.queuedID)

        val sent = jsonObjectOf("accepted" to "sent").decode<SendResult>()
        assertEquals(SendAcceptance.sent, sent.accepted)
        assertNull(sent.queuedID)
    }

    // Terminal hints

    /** A terminal session says how to make the next run controllable. */
    @Test
    fun attachHints() = runTest {
        assertEquals(ChatStore.AttachHint.installShim,
                     store(state = SessionState.readonly, control = SessionControl.terminal,
                           agent = DemoFixtures.claudeWithoutShim).attachHint)
        assertEquals(ChatStore.AttachHint.restartSession,
                     store(state = SessionState.readonly, control = SessionControl.terminal).attachHint)
        val codex = AgentInfo(agent = "codex", available = true, attach = AgentAttach.daemon)
        assertEquals(ChatStore.AttachHint.startDaemon,
                     store(state = SessionState.readonly, control = SessionControl.terminal, agent = codex).attachHint)
        // Amendment A26: pi attaches through the extension the device installs.
        val pi = AgentInfo(agent = "pi", available = true, attach = AgentAttach.extension)
        assertEquals(ChatStore.AttachHint.installExtension,
                     store(state = SessionState.readonly, control = SessionControl.terminal, agent = pi).attachHint)
        // Amendment A28: Grok Build's terminals join the leader only where the person's own
        // configuration puts them there.
        val grok = AgentInfo(agent = "grok", available = true, attach = AgentAttach.leader)
        assertEquals(ChatStore.AttachHint.enableLeader,
                     store(state = SessionState.readonly, control = SessionControl.terminal, agent = grok).attachHint)
        assertEquals(ChatStore.AttachHint.enableLeader,
                     store(state = SessionState.readonly, control = SessionControl.terminal,
                           agent = DemoFixtures.grokWithoutLeader).attachHint)
        // A prepared machine blames this `grok` instead: it was started outside the leader, and
        // restarting it is what joins.
        assertEquals(ChatStore.AttachHint.restartSession,
                     store(state = SessionState.readonly, control = SessionControl.terminal,
                           agent = DemoFixtures.grok).attachHint)
    }

    /** An agent that cannot be attached says nothing about it. */
    @Test
    fun noHintWithoutAnAttachment() = runTest {
        assertNull(store(state = SessionState.readonly, control = SessionControl.terminal,
                         agent = AgentInfo(agent = "claude", available = true)).attachHint)
        assertNull(store(state = SessionState.readonly, control = SessionControl.terminal, agent = null).attachHint)
        assertNull(store(state = SessionState.idle, control = SessionControl.shared).attachHint)
        assertNull(store(state = SessionState.idle, control = SessionControl.remote).attachHint)
    }

    // The delivery chip

    /**
     * A held message is a queue entry until the CLI takes it. Amendment A19: the device holds a
     * message sent into a running attached turn as a queue entry and nothing else. The optimistic row
     * the app drew when it sent retires into that queue, and the block arrives only when the CLI takes
     * it — after the output of the turn it waited for.
     */
    @Test
    fun heldMessageIsAQueueEntry() {
        fun event(seq: Int, kind: String, vararg fields: Pair<String, Any?>): SessionEvent =
            jsonObjectOf("seq" to seq, "ts" to seq, "kind" to kind, *fields).decode()

        val timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "drop I, L, O and U"))
        assertEquals("req-1", timeline.roots.lastOrNull()?.pending?.id)

        // The device answers `queued` and publishes the queue. No block yet.
        timeline.apply(event(12, SessionEvent.queueKind,
                             "pending" to listOf(mapOf("id" to "req-1", "text" to "drop I, L, O and U", "ts" to 1))))
        assertEquals(listOf("req-1"), timeline.queue.map { it.id })
        assertTrue(timeline.roots.all { it.pending == null },
                   "the optimistic row moved into the queue rather than staying in the transcript")
        assertFalse(timeline.entries.any { it.userMessage != null }, "and no block was drawn for it")

        // The turn the message waited for finishes.
        timeline.apply(event(16, SessionEvent.assistantTextKind, "block_id" to "a-1", "text" to "Done.", "done" to true))
        // The CLI takes the message: the block appears, after that output.
        timeline.apply(event(18, SessionEvent.userMessageKind, "block_id" to "req-1", "text" to "drop I, L, O and U",
                             "source" to "remote", "delivery" to "delivered"))
        timeline.apply(event(19, SessionEvent.queueKind, "pending" to emptyList<Any>()))
        assertEquals(MessageDelivery.delivered, timeline.entry(id = "req-1")?.userMessage?.delivery)
        assertTrue(timeline.queue.isEmpty())
        assertEquals(listOf("a-1", "req-1"), timeline.roots.map { it.id }.takeLast(2),
                     "and it is drawn after the output of the turn it waited for")
    }

    /** An absorbed message keeps its block so the re-send replaces it. */
    @Test
    fun absorbedKeepsTheBlock() {
        fun message(seq: Int, delivery: String): SessionEvent = jsonObjectOf(
            "seq" to seq, "ts" to seq, "kind" to SessionEvent.userMessageKind, "block_id" to "u-absorbed",
            "text" to "also update the docstring", "source" to "remote", "delivery" to delivery,
        ).decode()

        val timeline = Timeline()
        timeline.apply(message(24, delivery = "absorbed"))
        assertEquals(MessageDelivery.absorbed, timeline.entry(id = "u-absorbed")?.userMessage?.delivery)
        timeline.apply(message(31, delivery = "delivered"))
        assertEquals(1, timeline.entries.size)
        assertEquals(MessageDelivery.delivered, timeline.entry(id = "u-absorbed")?.userMessage?.delivery)
    }

    // The demo

    /** The demo holds a message, then injects it into the attached session. */
    @Test
    fun demoInjection() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val session = Session(sessionID = DemoFixtures.sharedSessionID, deviceID = DemoFixtures.macDeviceID, agent = "claude",
                              title = "Tidy the release notes", cwd = "/tmp", state = SessionState.idle,
                              origin = EventSource.terminal, control = SessionControl.shared)
        val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
        chat.agent = DemoFixtures.claude
        backgroundScope.launch {
            gateway.events.collect { event -> if (event is GatewayEvent.Frame) chat.receive(event.frame) }
        }

        chat.draft = "mention the iOS app too"
        chat.send()
        // Amendment A19: while the device holds it, it is a queue entry only.
        settle(timeout = 10.seconds) { chat.timeline.queue.size == 1 }
        assertTrue(chat.timeline.roots.all { it.pending == null })
        assertFalse(chat.timeline.entries.any { it.userMessage?.source == EventSource.remote })

        settle(timeout = 10.seconds) { chat.timeline.entries.any { it.userMessage?.delivery == MessageDelivery.delivered } }
        assertEquals(1, chat.timeline.entries.count { it.userMessage?.source == EventSource.remote })
        assertTrue(chat.timeline.queue.isEmpty())

        settle(timeout = 10.seconds) { chat.timeline.pendingRequest?.approval != null }
        val approval = chat.timeline.pendingRequest?.approval
        assertEquals(listOf("allow", "deny"), approval?.options?.map { it.id })
        assertNull(approval?.diff)
    }
}
